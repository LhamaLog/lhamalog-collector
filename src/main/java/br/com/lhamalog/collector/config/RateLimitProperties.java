package br.com.lhamalog.collector.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-tenant OTLP rate limit (LLT-183). Controllers share one counter for logs and traces.
 *
 * <p>Requests over {@code requests-per-second} in a fixed 1 s window receive Retry-After
 * {@code retry-after} (default 5 s). Five violations within {@code violation-window} block the
 * tenant for {@code block-duration}; blocked requests receive Retry-After {@code block-retry-after}.
 *
 * <p>Redis-down policy default fail-open remains provisional until Q13 confirmation.
 */
@ConfigurationProperties("lhamalog.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("20") int requestsPerSecond,
		@DefaultValue("5s") Duration retryAfter,
		@DefaultValue("5") int violationsBeforeBlock,
		@DefaultValue("60s") Duration violationWindow,
		@DefaultValue("300s") Duration blockDuration,
		@DefaultValue("300s") Duration blockRetryAfter,
		/** PROVISIONAL default until Q13 confirmation. */
		@DefaultValue("FAIL_OPEN") StoreUnavailablePolicy onStoreUnavailable) {

	public enum StoreUnavailablePolicy {
		/** Allow the request and emit WARN + metric. */
		FAIL_OPEN,
		/** Refuse as rate-limited when the store cannot be reached. */
		FAIL_CLOSED
	}

	public RateLimitProperties {
		if (requestsPerSecond <= 0) {
			throw new IllegalArgumentException("lhamalog.rate-limit.requests-per-second must be greater than 0");
		}
		if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
			throw new IllegalArgumentException("lhamalog.rate-limit.retry-after must be greater than 0");
		}
		if (violationsBeforeBlock <= 0) {
			throw new IllegalArgumentException("lhamalog.rate-limit.violations-before-block must be greater than 0");
		}
		if (violationWindow == null || violationWindow.isNegative() || violationWindow.isZero()) {
			throw new IllegalArgumentException("lhamalog.rate-limit.violation-window must be greater than 0");
		}
		if (blockDuration == null || blockDuration.isNegative() || blockDuration.isZero()) {
			throw new IllegalArgumentException("lhamalog.rate-limit.block-duration must be greater than 0");
		}
		if (blockRetryAfter == null || blockRetryAfter.isNegative() || blockRetryAfter.isZero()) {
			throw new IllegalArgumentException("lhamalog.rate-limit.block-retry-after must be greater than 0");
		}
		if (onStoreUnavailable == null) {
			onStoreUnavailable = StoreUnavailablePolicy.FAIL_OPEN;
		}
	}

	public long retryAfterSeconds() {
		return retryAfter.toSeconds();
	}

	public long violationWindowSeconds() {
		return violationWindow.toSeconds();
	}

	public long blockDurationSeconds() {
		return blockDuration.toSeconds();
	}

	public long blockRetryAfterSeconds() {
		return blockRetryAfter.toSeconds();
	}
}
