package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.BatchAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * T012 / T018 / T022 / T023 / T036 — rate before body, size before reserve, order, and observability.
 */
class BatchIngestionRateAndSizeTest {

	@Test
	void rateCheckRunsAfterAuthAndBeforePayloadRead() {
		AtomicBoolean bodyRead = new AtomicBoolean();
		AtomicInteger rateCalls = new AtomicInteger();
		TenantRateLimiter limiter = tenant -> {
			rateCalls.incrementAndGet();
			assertThat(bodyRead).isFalse();
			return TenantRateLimiter.Verdict.LIMITED;
		};
		IngestionControls controls = new IngestionControls(limiter, Integer.MAX_VALUE, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);

		IngestionDecision decision = BatchIngestion.run(token -> new TokenValidationResult.Valid("t", "p"), "tok",
				() -> {
					bodyRead.set(true);
					return "body";
				}, ignored -> new BatchAssessment<>(List.of("r"), 0),
				(tenant, product, request, records) -> {
					throw new AssertionError("sink");
				}, "records", "invalid", null, BatchSanitizer.identity(), controls);

		assertThat(decision).isEqualTo(new IngestionDecision.RateLimited(5));
		assertThat(rateCalls).hasValue(1);
		assertThat(bodyRead).isFalse();
	}

	@Test
	void rateLimitedSkipsQuotaReservation() {
		AtomicInteger reserves = new AtomicInteger();
		QuotaGate<String, String> quota = gate(reserves, QuotaGate.Reservation.Outcome.AUTHORIZED);
		IngestionControls controls = new IngestionControls(tenant -> TenantRateLimiter.Verdict.LIMITED,
				Integer.MAX_VALUE, 300, 250, 300, CoreUnavailableAttemptTracker.alwaysRetryable(300),
				IngestionObservability.NOOP);

		IngestionDecision decision = run(quota, controls, () -> "body");

		assertThat(decision).isEqualTo(new IngestionDecision.RateLimited(250));
		assertThat(reserves).hasValue(0);
	}

	@Test
	void blockedTenantUsesBlockRetryAfter() {
		IngestionControls controls = new IngestionControls(tenant -> TenantRateLimiter.Verdict.BLOCKED,
				Integer.MAX_VALUE, 300, 5, 300, CoreUnavailableAttemptTracker.alwaysRetryable(300),
				IngestionObservability.NOOP);
		assertThat(run(null, controls, () -> "body")).isEqualTo(new IngestionDecision.RateLimited(300));
	}

	@Test
	void applicationBatchTooLargeSkipsQuotaReservation() {
		AtomicInteger reserves = new AtomicInteger();
		QuotaGate<ExportLogsServiceRequest, String> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, ExportLogsServiceRequest request,
					List<String> accepted) {
				reserves.incrementAndGet();
				return new Reservation(Reservation.Outcome.AUTHORIZED, "r1");
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}
		};
		ExportLogsServiceRequest large = largeRequest(6 * 1024 * 1024);
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 5 * 1024 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);

		IngestionDecision decision = BatchIngestion.run(token -> new TokenValidationResult.Valid("t", "p"), "tok",
				() -> large, ignored -> new BatchAssessment<>(List.of("r"), 0),
				(tenant, product, request, records) -> {
					throw new AssertionError("sink");
				}, "records", "invalid", quota, BatchSanitizer.identity(), controls);

		assertThat(decision).isInstanceOf(IngestionDecision.BatchTooLarge.class);
		assertThat(reserves).hasValue(0);
		assertThat(large.getSerializedSize()).isGreaterThan(5 * 1024 * 1024);
	}

	@Test
	void rateAndSizeOkWithInsufficientBalanceStillRefusesWithQuotaExceeded() {
		AtomicInteger reserves = new AtomicInteger();
		QuotaGate<String, String> quota = gate(reserves, QuotaGate.Reservation.Outcome.INSUFFICIENT_BALANCE);
		IngestionControls controls = IngestionControls.permissive();

		IngestionDecision decision = run(quota, controls, () -> "body");

		assertThat(decision).isEqualTo(new IngestionDecision.QuotaExceeded());
		assertThat(reserves).hasValue(1);
	}

	@Test
	void observabilityEmitsDistinctReasons() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability observability = new IngestionObservability(meters);
		IngestionControls rateControls = new IngestionControls(tenant -> TenantRateLimiter.Verdict.LIMITED,
				Integer.MAX_VALUE, 300, 5, 300, CoreUnavailableAttemptTracker.alwaysRetryable(300), observability);
		run(null, rateControls, () -> "body");
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "rate_limit").counter().count())
				.isEqualTo(1.0);

		IngestionControls blockedControls = new IngestionControls(tenant -> TenantRateLimiter.Verdict.BLOCKED,
				Integer.MAX_VALUE, 300, 5, 300, CoreUnavailableAttemptTracker.alwaysRetryable(300), observability);
		run(null, blockedControls, () -> "body");
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "rate_limit_blocked").counter().count())
				.isEqualTo(1.0);

		IngestionControls sizeControls = new IngestionControls(TenantRateLimiter.allowAll(), 1, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), observability);
		BatchIngestion.run(token -> new TokenValidationResult.Valid("t", "p"), "tok", () -> largeRequest(64),
				ignored -> new BatchAssessment<>(List.of("r"), 0),
				(tenant, product, request, records) -> {
					throw new AssertionError("sink");
				}, "records", "invalid", null, BatchSanitizer.identity(), sizeControls);
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "batch_too_large").counter().count())
				.isEqualTo(1.0);

		IngestionControls balanceControls = new IngestionControls(TenantRateLimiter.allowAll(), Integer.MAX_VALUE, 300,
				5, 300, CoreUnavailableAttemptTracker.alwaysRetryable(300), observability);
		run(gate(new AtomicInteger(), QuotaGate.Reservation.Outcome.INSUFFICIENT_BALANCE), balanceControls, () -> "body");
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "insufficient_balance").counter().count())
				.isEqualTo(1.0);

		run(gate(new AtomicInteger(), QuotaGate.Reservation.Outcome.UNAVAILABLE), balanceControls, () -> "body");
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "quota_unavailable").counter().count())
				.isEqualTo(1.0);

		run(gate(new AtomicInteger(), QuotaGate.Reservation.Outcome.SUSPENDED), balanceControls, () -> "body");
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "subscription_suspended").counter().count())
				.isEqualTo(1.0);
	}

	private static ExportLogsServiceRequest largeRequest(int bodyBytes) {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder()
						.addScopeLogs(ScopeLogs.newBuilder()
								.addLogRecords(LogRecord.newBuilder()
										.setBody(AnyValue.newBuilder().setStringValue("x".repeat(bodyBytes))))))
				.build();
	}

	private static QuotaGate<String, String> gate(AtomicInteger reserves, QuotaGate.Reservation.Outcome outcome) {
		return new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, String request, List<String> accepted) {
				reserves.incrementAndGet();
				return new Reservation(outcome, "r1");
			}

			@Override
			public String batchKey(String tenantId, String productId, String request, List<String> accepted) {
				return "r1";
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}
		};
	}

	private static IngestionDecision run(QuotaGate<String, String> quota, IngestionControls controls,
			OtlpPayload<String> payload) {
		return BatchIngestion.run(token -> new TokenValidationResult.Valid("t", "p"), "tok", payload,
				ignored -> new BatchAssessment<>(List.of("r"), 0),
				(tenant, product, request, records) -> {
				}, "records", "invalid", quota, BatchSanitizer.identity(), controls);
	}
}
