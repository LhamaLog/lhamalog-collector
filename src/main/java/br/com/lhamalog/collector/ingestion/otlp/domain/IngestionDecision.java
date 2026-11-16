// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.util.Objects;

/**
 * Outcome of one OTLP export, before it becomes an HTTP or gRPC response (T005).
 *
 * <p>See {@code specs/001-otlp-receiver/data-model.md}, section IngestionDecision. A failure of the
 * destination in the middle of a valid batch always ends in {@link DestinationUnavailable}, even when
 * the assessment already rejected other records.
 */
public sealed interface IngestionDecision {

	/** Missing, empty, unknown, revoked or expired token, or token of a missing product. Sink not called. */
	record Unauthorized() implements IngestionDecision {
	}

	/** Reading {@code product_tokens} or {@code products} failed. Sink not called. */
	record CatalogUnavailable() implements IngestionDecision {
	}

	/**
	 * Unreadable body, unsupported encoding, body above the limit, or every record invalid (sink not called);
	 * or every valid record refused by the destination as invalid data (reason
	 * {@link Reason#REJECTED_BY_DESTINATION}).
	 */
	record InvalidPayload(Reason reason, String message) implements IngestionDecision {

		public InvalidPayload {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(message, "message");
		}

	}

	/** Why a payload was refused as a whole. */
	enum Reason {

		/** Body that cannot be decoded as the expected OTLP message. */
		MALFORMED,

		/** Content type or content encoding not supported by the receiver. */
		UNSUPPORTED_ENCODING,

		/** Decompressed body or gRPC message above the transport limit ({@code max-message-size}). */
		TOO_LARGE,

		/** Decompressed body or decoded message above the application batch limit ({@code max-batch-size}). */
		BATCH_TOO_LARGE,

		/** Well formed batch in which every record is invalid. */
		ALL_RECORDS_INVALID,

		/**
		 * The destination refused the records as invalid data (OpenObserve 4xx or per record failures). Not
		 * retryable: sending the same data again gets the same answer (T040, FR-020).
		 */
		REJECTED_BY_DESTINATION

	}

	/** Well formed batch with zero records. Sink not called. */
	record EmptySuccess() implements IngestionDecision {
	}

	/** One or more valid records, no rejection, and the sink confirmed all of them. */
	record Accepted(int acceptedCount) implements IngestionDecision {
	}

	/**
	 * Some records rejected, by the assessment or by the destination as invalid data; the destination
	 * confirmed the others.
	 */
	record PartiallyAccepted(int acceptedCount, int rejectedCount, String errorMessage) implements IngestionDecision {

		public PartiallyAccepted {
			if (rejectedCount <= 0 || acceptedCount <= 0) {
				throw new IllegalArgumentException("partial acceptance needs accepted and rejected records");
			}
			Objects.requireNonNull(errorMessage, "errorMessage");
		}

	}

	/** There were valid records and the sink did not confirm them. Nothing is confirmed to the sender. */
	record DestinationUnavailable(long retryAfterSeconds) implements IngestionDecision {
	}

	/** Core refused the complete eligible batch because its shared cycle balance is exhausted. */
	record QuotaExceeded() implements IngestionDecision {
	}

	/**
	 * Core cannot identify an active paid allowance, or cannot be reached safely.
	 *
	 * @param retryAfterSeconds value for HTTP {@code Retry-After} / gRPC trailer (LLT-183 default 300)
	 */
	record QuotaUnavailable(long retryAfterSeconds) implements IngestionDecision {
		public QuotaUnavailable {
			if (retryAfterSeconds < 0) {
				throw new IllegalArgumentException("retryAfterSeconds must not be negative");
			}
		}
	}

	/**
	 * Core-unavailable retry budget exhausted for this batch (LLT-183 D4): non-retryable drop.
	 * Maps to HTTP 500 / gRPC INTERNAL without Retry-After.
	 */
	record QuotaUnavailableExhausted() implements IngestionDecision {
	}

	/**
	 * Tenant exceeded the per-second OTLP request rate, or is temporarily blocked after repeated
	 * violations (LLT-183 D5).
	 *
	 * @param retryAfterSeconds {@code lhamalog.rate-limit.retry-after} (default 5) or
	 *        {@code block-retry-after} (default 300) when blocked
	 */
	record RateLimited(long retryAfterSeconds) implements IngestionDecision {
		public RateLimited {
			if (retryAfterSeconds < 0) {
				throw new IllegalArgumentException("retryAfterSeconds must not be negative");
			}
		}
	}

	/** Decoded/decompressed batch above {@code lhamalog.otlp.max-batch-size} (LLT-183). */
	record BatchTooLarge() implements IngestionDecision {
	}

	/**
	 * Subscription is suspended (Core {@code SUSPENDED}); variant reserved for mapping without Core client
	 * wiring in this phase.
	 */
	record Suspended() implements IngestionDecision {
	}

}
