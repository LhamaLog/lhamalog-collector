package br.com.lhamalog.collector.ingestion.otlp.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized refusal metrics and logs for LLT-183. Counter tags use stable English reason labels only
 * (no tenant, product, token, or payload).
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

	private static final Logger log = LoggerFactory.getLogger(IngestionObservability.class);

	private final MeterRegistry meters;

	public IngestionObservability(MeterRegistry meters) {
		this.meters = meters;
	}

	public void refused(String reason) {
		increment("lhamalog.ingestion.refused", reason);
		log.info("OTLP ingestion refused reason={}", reason);
	}

	public void rateLimitStoreUnavailable() {
		increment("lhamalog.ingestion.refused", REASON_RATE_LIMIT_STORE_UNAVAILABLE);
		log.warn("OTLP rate-limit store unavailable reason={}", REASON_RATE_LIMIT_STORE_UNAVAILABLE);
	}

	public void quotaUnavailableAttemptStoreUnavailable() {
		increment("lhamalog.ingestion.refused", REASON_QUOTA_UNAVAILABLE_ATTEMPT_STORE_UNAVAILABLE);
		log.warn("OTLP Core-unavailable attempt store unavailable reason={}",
				REASON_QUOTA_UNAVAILABLE_ATTEMPT_STORE_UNAVAILABLE);
	}

	private void increment(String name, String reason) {
		if (meters != null) {
			Counter.builder(name).tag("reason", reason).register(meters).increment();
		}
	}
}
