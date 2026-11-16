package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;

/**
 * Cross-cutting ingestion limits applied after auth: rate, application batch size, and Retry-After
 * values for Core-unavailable and rate-limit / block (LLT-183).
 */
public record IngestionControls(TenantRateLimiter rateLimiter, int maxBatchBytes,
		long unavailableRetryAfterSeconds, long rateLimitRetryAfterSeconds,
		long rateLimitBlockRetryAfterSeconds, CoreUnavailableAttemptTracker unavailableAttempts,
		IngestionObservability observability) {

	public static final long DEFAULT_UNAVAILABLE_RETRY_AFTER_SECONDS = 300L;
	public static final long DEFAULT_RATE_LIMIT_RETRY_AFTER_SECONDS = 5L;
	public static final long DEFAULT_RATE_LIMIT_BLOCK_RETRY_AFTER_SECONDS = 300L;
	public static final int DEFAULT_MAX_BATCH_BYTES = 5 * 1024 * 1024;

	public IngestionControls {
		if (rateLimiter == null) {
			rateLimiter = TenantRateLimiter.allowAll();
		}
		if (maxBatchBytes <= 0) {
			throw new IllegalArgumentException("maxBatchBytes must be greater than 0");
		}
		if (unavailableRetryAfterSeconds < 0 || rateLimitRetryAfterSeconds < 0
				|| rateLimitBlockRetryAfterSeconds < 0) {
			throw new IllegalArgumentException("retry-after seconds must not be negative");
		}
		if (unavailableAttempts == null) {
			unavailableAttempts = CoreUnavailableAttemptTracker.alwaysRetryable(unavailableRetryAfterSeconds);
		}
		if (observability == null) {
			observability = IngestionObservability.NOOP;
		}
	}

	/** Defaults that skip rate limiting and keep a high batch ceiling for focused unit tests. */
	public static IngestionControls permissive() {
		return new IngestionControls(TenantRateLimiter.allowAll(), Integer.MAX_VALUE,
				DEFAULT_UNAVAILABLE_RETRY_AFTER_SECONDS, DEFAULT_RATE_LIMIT_RETRY_AFTER_SECONDS,
				DEFAULT_RATE_LIMIT_BLOCK_RETRY_AFTER_SECONDS,
				CoreUnavailableAttemptTracker.alwaysRetryable(DEFAULT_UNAVAILABLE_RETRY_AFTER_SECONDS),
				IngestionObservability.NOOP);
	}
}
