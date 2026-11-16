// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.BatchAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkRejectedException;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import br.com.lhamalog.collector.logging.CorrelationId;
import br.com.lhamalog.collector.quota.ReservationCorrelation;
import br.com.lhamalog.collector.quota.RetentionCorrelation;
import com.google.protobuf.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flow shared by logs and traces: authenticate, rate-limit, read/size, assess, deliver, decide.
 * Order (LLT-183 D8): auth → rate → size → assess/quota. The sink is never called for an empty or
 * fully invalid batch, and a sink failure is never turned into a (partial) success.
 *
 * <p>Destination answers are split by cause (T040, FR-020): unavailability (5xx, timeout, network) becomes
 * {@code DestinationUnavailable}, retryable; a refusal of the data itself becomes a non retryable
 * {@code PartiallyAccepted} when the destination stored some records, or {@code InvalidPayload} with reason
 * {@code REJECTED_BY_DESTINATION} when it stored none.
 */
final class BatchIngestion {

	private static final Logger log = LoggerFactory.getLogger(BatchIngestion.class);
	private static final ExecutorService SANITIZATION_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "otlp-sanitize");
		thread.setDaemon(true);
		return thread;
	});
	private static final long SANITIZATION_TIMEOUT_SECONDS = 5;

	@FunctionalInterface
	interface Delivery<R, T> {

		void deliver(String tenantId, String productId, R request, List<T> records);

	}

	static final String DESTINATION_REJECTION_REASON = "refused by the destination as invalid data";

	private BatchIngestion() {
	}

	static <R, T> IngestionDecision run(TokenValidator tokens, String bearerToken, OtlpPayload<R> payload,
			Function<R, BatchAssessment<T>> assessment, Delivery<R, T> sink, String recordName, String rejectionReason) {
		return run(tokens, bearerToken, payload, assessment, sink, recordName, rejectionReason, null);
	}

	static <R, T> IngestionDecision run(TokenValidator tokens, String bearerToken, OtlpPayload<R> payload,
			Function<R, BatchAssessment<T>> assessment, Delivery<R, T> sink, String recordName, String rejectionReason,
			QuotaGate<R, T> quota) {
		return run(tokens, bearerToken, payload, assessment, sink, recordName, rejectionReason, quota,
				BatchSanitizer.identity());
	}

	static <R, T> IngestionDecision run(TokenValidator tokens, String bearerToken, OtlpPayload<R> payload,
			Function<R, BatchAssessment<T>> assessment, Delivery<R, T> sink, String recordName, String rejectionReason,
			QuotaGate<R, T> quota, BatchSanitizer<R, T> sanitizer) {
		return run(tokens, bearerToken, payload, assessment, sink, recordName, rejectionReason, quota, sanitizer,
				IngestionControls.permissive());
	}

	static <R, T> IngestionDecision run(TokenValidator tokens, String bearerToken, OtlpPayload<R> payload,
			Function<R, BatchAssessment<T>> assessment, Delivery<R, T> sink, String recordName, String rejectionReason,
			QuotaGate<R, T> quota, BatchSanitizer<R, T> sanitizer, IngestionControls controls) {
		IngestionControls limits = controls == null ? IngestionControls.permissive() : controls;
		TokenValidationResult auth = tokens.validate(bearerToken);
		TokenValidationResult.Valid valid;
		switch (auth) {
			case TokenValidationResult.Unauthorized u -> {
				return new IngestionDecision.Unauthorized();
			}
			case TokenValidationResult.CatalogUnavailable c -> {
				return new IngestionDecision.CatalogUnavailable();
			}
			case TokenValidationResult.Valid v -> valid = v;
		}
		TenantRateLimiter.Verdict rateVerdict = limits.rateLimiter().check(valid.tenantId());
		if (rateVerdict == TenantRateLimiter.Verdict.BLOCKED) {
			limits.observability().refused(IngestionObservability.REASON_RATE_LIMIT_BLOCKED);
			return new IngestionDecision.RateLimited(limits.rateLimitBlockRetryAfterSeconds());
		}
		if (rateVerdict == TenantRateLimiter.Verdict.LIMITED) {
			limits.observability().refused(IngestionObservability.REASON_RATE_LIMIT);
			return new IngestionDecision.RateLimited(limits.rateLimitRetryAfterSeconds());
		}
		R request;
		try {
			request = payload.read();
		}
		catch (PayloadRejectedException ex) {
			if (ex.reason() == IngestionDecision.Reason.BATCH_TOO_LARGE) {
				limits.observability().refused(IngestionObservability.REASON_BATCH_TOO_LARGE);
				return new IngestionDecision.BatchTooLarge();
			}
			return new IngestionDecision.InvalidPayload(ex.reason(), ex.getMessage());
		}
		if (exceedsApplicationBatchSize(request, limits.maxBatchBytes())) {
			limits.observability().refused(IngestionObservability.REASON_BATCH_TOO_LARGE);
			return new IngestionDecision.BatchTooLarge();
		}
		BatchAssessment<T> batch = assessment.apply(request);
		if (batch.totalCount() == 0) {
			return new IngestionDecision.EmptySuccess();
		}
		if (batch.valid().isEmpty()) {
			return new IngestionDecision.InvalidPayload(IngestionDecision.Reason.ALL_RECORDS_INVALID,
					"all " + batch.rejectedCount() + " " + recordName + " are invalid: " + rejectionReason);
		}
		final List<T> eligible = batch.valid();
		List<T> accepted = eligible;
		String reservationId = null;
		Integer retentionDays = null;
		if (quota != null) {
			CompletableFuture<QuotaGate.Reservation> reservationFuture = CompletableFuture.supplyAsync(
					CorrelationId.propagate(() -> quota.reserve(valid.tenantId(), valid.productId(), request, eligible)),
					SANITIZATION_EXECUTOR);
			CompletableFuture<List<T>> sanitizerFuture = CompletableFuture.supplyAsync(
					CorrelationId.propagate(() -> sanitizer.sanitize(request, eligible)), SANITIZATION_EXECUTOR);
			QuotaGate.Reservation reservation;
			try {
				reservation = reservationFuture.get(SANITIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception unavailable) {
				sanitizerFuture.cancel(true);
				quota.coreUnavailable();
				return refuseCoreUnavailable(quota, limits, valid.tenantId(), valid.productId(), request, eligible,
						null);
			}
			switch (reservation.outcome()) {
				case INSUFFICIENT_BALANCE -> {
					limits.observability().refused(IngestionObservability.REASON_INSUFFICIENT_BALANCE);
					return new IngestionDecision.QuotaExceeded();
				}
				case SUSPENDED -> {
					limits.observability().refused(IngestionObservability.REASON_SUBSCRIPTION_SUSPENDED);
					return new IngestionDecision.Suspended();
				}
				case NO_PLAN, UNAVAILABLE -> {
					// NO_PLAN shares the same 503 path as Core unavailable (spec006); count toward budget.
					if (reservation.outcome() == QuotaGate.Reservation.Outcome.UNAVAILABLE) {
						quota.coreUnavailable();
					}
					return refuseCoreUnavailable(quota, limits, valid.tenantId(), valid.productId(), request, eligible,
							reservation.reservationId());
				}
				case AUTHORIZED -> {
					reservationId = reservation.reservationId();
					retentionDays = reservation.retentionDays();
				}
			}
			try {
				accepted = sanitizerFuture.get(SANITIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception failure) {
				releaseQuietly(quota, reservationId);
				if (failure instanceof java.util.concurrent.ExecutionException
						&& failure.getCause() instanceof BatchSanitizer.RejectedException) {
					return new IngestionDecision.InvalidPayload(IngestionDecision.Reason.MALFORMED,
							"batch rejected during sanitization");
				}
				// Sanitization failure is not Core-unavailable: plain 503 without attempt budget.
				limits.observability().refused(IngestionObservability.REASON_QUOTA_UNAVAILABLE);
				return new IngestionDecision.QuotaUnavailable(limits.unavailableRetryAfterSeconds());
			}
			try {
				quota.confirm(reservationId);
			}
			catch (RuntimeException unavailable) {
				quota.coreUnavailable();
				return refuseCoreUnavailable(quota, limits, valid.tenantId(), valid.productId(), request, eligible,
						reservationId);
			}
			limits.unavailableAttempts().clear(reservationId);
		}
		try {
			if (reservationId != null) {
				ReservationCorrelation.set(reservationId);
			}
			RetentionCorrelation.set(retentionDays);
			sink.deliver(valid.tenantId(), valid.productId(), request, accepted);
		}
		catch (SinkUnavailableException ex) {
			postDebitFailure(quota);
			// Records already on the read stream keep the as-received debit; refund only when nothing was published.
			if (!ex.publishedBeforeFailure()) {
				refundQuietly(quota, reservationId, "SINK_UNAVAILABLE");
			}
			// The successful confirm intentionally remains durable; the refund outbox compensates this case.
			log.warn(
					"event=ingestion.destination.not_confirmed | Destination did not confirm {} {} of product {}: {}",
					batch.valid().size(), recordName, valid.productId(), ex.getMessage());
			return new IngestionDecision.DestinationUnavailable(postDebitRetryAfter(reservationId, ex.retryAfterSeconds()));
		}
		catch (SinkRejectedException ex) {
			postDebitFailure(quota);
			// Partial OpenObserve acceptance already stored records under the reserved (as-received) debit.
			// Refund only when nothing was stored; otherwise the confirmed debit stays.
			if (ex.acceptedCount() == 0) {
				refundQuietly(quota, reservationId, "SINK_REJECTED");
			}
			log.warn(
					"event=ingestion.destination.refused | Destination refused {} of {} {} of product {} as invalid data: {}",
					ex.rejectedCount(), batch.valid().size(), recordName, valid.productId(), ex.getMessage());
			int rejected = batch.rejectedCount() + ex.rejectedCount();
			String message = describeRejections(batch.rejectedCount(), ex.rejectedCount(), recordName, rejectionReason);
			if (ex.acceptedCount() == 0) {
				return new IngestionDecision.InvalidPayload(IngestionDecision.Reason.REJECTED_BY_DESTINATION,
						"all " + rejected + " " + recordName + " were rejected: " + message);
			}
			return new IngestionDecision.PartiallyAccepted(ex.acceptedCount(), rejected,
					rejected + " " + recordName + " rejected: " + message);
		}
		catch (RuntimeException ex) {
			postDebitFailure(quota);
			refundQuietly(quota, reservationId, "SINK_FAILURE");
			log.warn("event=ingestion.destination.failed | Destination failed while delivering {} {} of product {}",
					batch.valid().size(), recordName, valid.productId(), ex);
			return new IngestionDecision.DestinationUnavailable(
					postDebitRetryAfter(reservationId, SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS));
		}
		finally {
			ReservationCorrelation.clear();
			RetentionCorrelation.clear();
		}
		if (batch.rejectedCount() == 0) {
			return new IngestionDecision.Accepted(batch.valid().size());
		}
		return new IngestionDecision.PartiallyAccepted(batch.valid().size(), batch.rejectedCount(),
				batch.rejectedCount() + " " + recordName + " rejected: " + rejectionReason);
	}

	/**
	 * Core unavailable / timeout / 5xx / NO_PLAN (same 503 path as spec006). Counts attempts against the
	 * deterministic reservation id; attempt 6+ drops the batch as non-retryable.
	 */
	private static <R, T> IngestionDecision refuseCoreUnavailable(QuotaGate<R, T> quota, IngestionControls limits,
			String tenantId, String productId, R request, List<T> eligible, String knownReservationId) {
		String batchKey = knownReservationId;
		if (batchKey == null || batchKey.isBlank()) {
			batchKey = quota.batchKey(tenantId, productId, request, eligible);
		}
		CoreUnavailableAttemptResult result = limits.unavailableAttempts().record(batchKey);
		return switch (result) {
			case CoreUnavailableAttemptResult.WithinBudget within -> {
				limits.observability().refused(IngestionObservability.REASON_QUOTA_UNAVAILABLE);
				log.warn(
						"event=quota.core_unavailable.retry | Core unavailable | tenantId={} productId={} attempt={}",
						tenantId, productId, within.attempt());
				yield new IngestionDecision.QuotaUnavailable(within.retryAfterSeconds());
			}
			case CoreUnavailableAttemptResult.Exhausted exhausted -> {
				limits.observability().refused(IngestionObservability.REASON_QUOTA_UNAVAILABLE_EXHAUSTED);
				log.warn(
						"event=quota.core_unavailable.exhausted | core unavailable: retry budget exhausted, batch dropped | tenantId={} productId={} attempt={}",
						tenantId, productId, exhausted.attempt());
				yield new IngestionDecision.QuotaUnavailableExhausted();
			}
			case CoreUnavailableAttemptResult.StoreError storeError -> {
				limits.observability().refused(IngestionObservability.REASON_QUOTA_UNAVAILABLE);
				log.warn(
						"event=quota.core_unavailable.attempt_store_error | Core unavailable attempt store error; plain 503 without budget | tenantId={} productId={}",
						tenantId, productId);
				yield new IngestionDecision.QuotaUnavailable(limits.unavailableRetryAfterSeconds());
			}
		};
	}

	static boolean exceedsApplicationBatchSize(Object request, int maxBatchBytes) {
		if (maxBatchBytes == Integer.MAX_VALUE || !(request instanceof Message message)) {
			return false;
		}
		return message.getSerializedSize() > maxBatchBytes;
	}

	private static long postDebitRetryAfter(String reservationId, long fallbackSeconds) {
		return reservationId != null ? 120L : fallbackSeconds;
	}

	private static <R, T> void postDebitFailure(QuotaGate<R, T> quota) {
		if (quota != null) {
			quota.postDebitWriteFailure();
		}
	}

	private static <R, T> void releaseQuietly(QuotaGate<R, T> quota, String reservationId) {
		try {
			quota.release(reservationId);
		}
		catch (RuntimeException releaseFailure) {
			log.warn("event=quota.reservation.release_failed | Could not release failed reservation {}", reservationId,
					releaseFailure);
		}
	}

	private static <R, T> void refundQuietly(QuotaGate<R, T> quota, String reservationId, String reason) {
		if (quota == null || reservationId == null) {
			return;
		}
		try {
			quota.refund(reservationId, reason);
		}
		catch (RuntimeException enqueueFailure) {
			log.error("event=quota.refund.enqueue_failed | Could not enqueue refund for confirmed reservation {}",
					reservationId, enqueueFailure);
		}
	}

	private static String describeRejections(int byAssessment, int byDestination, String recordName,
			String rejectionReason) {
		String destination = byDestination + " " + DESTINATION_REJECTION_REASON;
		return byAssessment == 0 ? destination
				: byAssessment + " " + rejectionReason + "; " + destination;
	}

}
