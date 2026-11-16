package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized ingestion metrics and logs. Counter/timer tags use stable English labels only
 * (no tenant, product, token, or payload). LLT-183 refusals + LLT-187 accepted/latency meters.
 */
public final class IngestionObservability {

	public static final IngestionObservability NOOP = new IngestionObservability(null);

	public static final String REASON_RATE_LIMIT = "rate_limit";
	public static final String REASON_RATE_LIMIT_BLOCKED = "rate_limit_blocked";
	public static final String REASON_BATCH_TOO_LARGE = "batch_too_large";
	public static final String REASON_INSUFFICIENT_BALANCE = "insufficient_balance";
	public static final String REASON_SUBSCRIPTION_SUSPENDED = "subscription_suspended";
	public static final String REASON_QUOTA_UNAVAILABLE = "quota_unavailable";
	public static final String REASON_QUOTA_UNAVAILABLE_EXHAUSTED = "quota_unavailable_exhausted";
	public static final String REASON_RATE_LIMIT_STORE_UNAVAILABLE = "rate_limit_store_unavailable";
	public static final String REASON_QUOTA_UNAVAILABLE_ATTEMPT_STORE_UNAVAILABLE =
			"quota_unavailable_attempt_store_unavailable";

	public static final String OUTCOME_ACCEPTED = "accepted";
	public static final String OUTCOME_REFUSED = "refused";
	public static final String OUTCOME_FAILED = "failed";

	private static final Logger log = LoggerFactory.getLogger(IngestionObservability.class);

	private static final Duration[] LATENCY_SLOS = {
			Duration.ofMillis(5),
			Duration.ofMillis(10),
			Duration.ofMillis(25),
			Duration.ofMillis(50),
			Duration.ofMillis(100),
			Duration.ofMillis(250),
			Duration.ofMillis(500),
			Duration.ofSeconds(1),
			Duration.ofMillis(2500),
			Duration.ofSeconds(5),
			Duration.ofSeconds(10),
			Duration.ofSeconds(30)
	};

	private final MeterRegistry meters;

	public IngestionObservability(MeterRegistry meters) {
		this.meters = meters;
	}

	public void refused(String reason) {
		increment("lhamalog.ingestion.refused", reason);
		log.info("event=ingestion.refused | OTLP ingestion refused | reason={}", reason);
	}

	public void rateLimitStoreUnavailable() {
		increment("lhamalog.ingestion.refused", REASON_RATE_LIMIT_STORE_UNAVAILABLE);
		log.warn(
				"event=ingestion.rate_limit.store_unavailable | OTLP rate-limit store unavailable | reason={}",
				REASON_RATE_LIMIT_STORE_UNAVAILABLE);
	}

	public void quotaUnavailableAttemptStoreUnavailable() {
		increment("lhamalog.ingestion.refused", REASON_QUOTA_UNAVAILABLE_ATTEMPT_STORE_UNAVAILABLE);
		log.warn(
				"event=quota.core_unavailable.attempt_store_unavailable | OTLP Core-unavailable attempt store unavailable | reason={}",
				REASON_QUOTA_UNAVAILABLE_ATTEMPT_STORE_UNAVAILABLE);
	}

	/**
	 * Records accepted volume after sink/publish succeeded. Bytes must be the canonical usage measure.
	 */
	public void accepted(String signal, String protocol, long records, long bytes) {
		if (meters == null || records <= 0) {
			return;
		}
		Counter.builder("lhamalog.ingestion.accepted.records")
				.tag("signal", signal)
				.tag("protocol", protocol)
				.register(meters)
				.increment(records);
		Counter.builder("lhamalog.ingestion.accepted.bytes")
				.tag("signal", signal)
				.tag("protocol", protocol)
				.register(meters)
				.increment(Math.max(0L, bytes));
	}

	public void latency(String signal, String protocol, String outcome, Duration duration) {
		if (meters == null || duration == null || duration.isNegative()) {
			return;
		}
		Timer.builder("lhamalog.ingestion.latency")
				.tag("signal", signal)
				.tag("protocol", protocol)
				.tag("outcome", outcome)
				.serviceLevelObjectives(LATENCY_SLOS)
				.publishPercentileHistogram()
				.register(meters)
				.record(duration.toNanos(), TimeUnit.NANOSECONDS);
	}

	public static String outcomeOf(IngestionDecision decision) {
		if (decision == null) {
			return OUTCOME_FAILED;
		}
		return switch (decision) {
			case IngestionDecision.Accepted a -> OUTCOME_ACCEPTED;
			case IngestionDecision.PartiallyAccepted p -> OUTCOME_ACCEPTED;
			case IngestionDecision.EmptySuccess e -> OUTCOME_ACCEPTED;
			case IngestionDecision.DestinationUnavailable d -> OUTCOME_FAILED;
			case IngestionDecision.CatalogUnavailable c -> OUTCOME_FAILED;
			default -> OUTCOME_REFUSED;
		};
	}

	private void increment(String name, String reason) {
		if (meters != null) {
			Counter.builder(name).tag("reason", reason).register(meters).increment();
		}
	}
}
