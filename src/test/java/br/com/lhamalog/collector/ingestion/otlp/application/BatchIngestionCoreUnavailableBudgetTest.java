package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.BatchAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** T043 — Core-unavailable attempt budget (D4): 1..5 → 503/300; 6th → exhausted; Core back clears. */
class BatchIngestionCoreUnavailableBudgetTest {

	@Test
	void attemptsOneThroughFiveAreRetryableThenSixthIsExhausted() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability observability = new IngestionObservability(meters);
		CountingTracker tracker = new CountingTracker(5, 300);
		IngestionControls controls = controls(tracker, observability);
		QuotaGate<String, String> quota = unavailableGate("batch-key-1");

		for (int i = 1; i <= 5; i++) {
			IngestionDecision decision = run(quota, controls);
			assertThat(decision).isEqualTo(new IngestionDecision.QuotaUnavailable(300));
		}
		IngestionDecision exhausted = run(quota, controls);
		assertThat(exhausted).isInstanceOf(IngestionDecision.QuotaUnavailableExhausted.class);
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "quota_unavailable_exhausted").counter()
				.count()).isEqualTo(1.0);
		assertThat(tracker.count("batch-key-1")).isEqualTo(6);
	}

	@Test
	void coreBackProcessesAndClearsCounter() {
		CountingTracker tracker = new CountingTracker(5, 300);
		IngestionControls controls = controls(tracker, IngestionObservability.NOOP);
		AtomicReference<QuotaGate.Reservation.Outcome> outcome = new AtomicReference<>(
				QuotaGate.Reservation.Outcome.UNAVAILABLE);
		QuotaGate<String, String> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, String request, List<String> accepted) {
				return new Reservation(outcome.get(), "batch-key-2");
			}

			@Override
			public String batchKey(String tenantId, String productId, String request, List<String> accepted) {
				return "batch-key-2";
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}
		};

		assertThat(run(quota, controls)).isEqualTo(new IngestionDecision.QuotaUnavailable(300));
		assertThat(tracker.count("batch-key-2")).isEqualTo(1);

		outcome.set(QuotaGate.Reservation.Outcome.AUTHORIZED);
		assertThat(run(quota, controls)).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(tracker.count("batch-key-2")).isZero();
	}

	@Test
	void redisErrorWhileCountingYieldsPlain503WithoutBudget() {
		CoreUnavailableAttemptTracker storeError = new CoreUnavailableAttemptTracker() {
			@Override
			public CoreUnavailableAttemptResult record(String batchKey) {
				return new CoreUnavailableAttemptResult.StoreError();
			}

			@Override
			public void clear(String batchKey) {
			}
		};
		IngestionControls controls = controls(storeError, IngestionObservability.NOOP);
		assertThat(run(unavailableGate("k"), controls)).isEqualTo(new IngestionDecision.QuotaUnavailable(300));
	}

	@Test
	void noPlanCountsTowardBudgetLikeCoreUnavailable() {
		CountingTracker tracker = new CountingTracker(5, 300);
		IngestionControls controls = controls(tracker, IngestionObservability.NOOP);
		QuotaGate<String, String> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, String request, List<String> accepted) {
				return new Reservation(Reservation.Outcome.NO_PLAN, "no-plan-key");
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}
		};
		assertThat(run(quota, controls)).isEqualTo(new IngestionDecision.QuotaUnavailable(300));
		assertThat(tracker.count("no-plan-key")).isEqualTo(1);
	}

	private static IngestionControls controls(CoreUnavailableAttemptTracker tracker,
			IngestionObservability observability) {
		return new IngestionControls(TenantRateLimiter.allowAll(), Integer.MAX_VALUE, 300, 5, 300, tracker,
				observability);
	}

	private static QuotaGate<String, String> unavailableGate(String key) {
		return new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, String request, List<String> accepted) {
				return new Reservation(Reservation.Outcome.UNAVAILABLE, key);
			}

			@Override
			public String batchKey(String tenantId, String productId, String request, List<String> accepted) {
				return key;
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}
		};
	}

	private static IngestionDecision run(QuotaGate<String, String> quota, IngestionControls controls) {
		return BatchIngestion.run(token -> new TokenValidationResult.Valid("t", "p"), "tok", () -> "body",
				ignored -> new BatchAssessment<>(List.of("r"), 0), (tenant, product, request, records) -> {
				}, "records", "invalid", quota, BatchSanitizer.identity(), controls);
	}

	/** In-memory attempt counter for unit tests. */
	private static final class CountingTracker implements CoreUnavailableAttemptTracker {
		private final int maxAttempts;
		private final long retryAfter;
		private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

		CountingTracker(int maxAttempts, long retryAfter) {
			this.maxAttempts = maxAttempts;
			this.retryAfter = retryAfter;
		}

		@Override
		public CoreUnavailableAttemptResult record(String batchKey) {
			int attempt = counts.computeIfAbsent(batchKey, k -> new AtomicInteger()).incrementAndGet();
			if (attempt > maxAttempts) {
				return new CoreUnavailableAttemptResult.Exhausted(attempt);
			}
			return new CoreUnavailableAttemptResult.WithinBudget(attempt, retryAfter);
		}

		@Override
		public void clear(String batchKey) {
			counts.remove(batchKey);
		}

		int count(String batchKey) {
			AtomicInteger value = counts.get(batchKey);
			return value == null ? 0 : value.get();
		}
	}
}
