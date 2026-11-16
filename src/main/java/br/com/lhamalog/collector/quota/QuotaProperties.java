package br.com.lhamalog.collector.quota;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Synchronous ingress quota protection. Disabled by default to preserve spec005 behaviour. */
@ConfigurationProperties("lhamalog.quota")
public record QuotaProperties(@DefaultValue("false") boolean enabled, @DefaultValue("5s") Duration timeout,
		@DefaultValue("10s") Duration refundWorkerInterval, @DefaultValue("250ms") Duration refundBackoffInitial,
		@DefaultValue("30s") Duration refundBackoffCeiling,
		/** Retry-After for Core/no-plan/unavailable path (LLT-183 D4); balance refusal stays 120 s. */
		@DefaultValue("300s") Duration unavailableRetryAfter,
		/** Max retryable Core-unavailable refusals per batch before drop (LLT-183 D4). */
		@DefaultValue("5") int unavailableMaxAttempts,
		/** TTL for the per-batch Core-unavailable attempt counter. */
		@DefaultValue("30m") Duration unavailableAttemptTtl) {
	public QuotaProperties {
		if (timeout == null || timeout.isNegative() || timeout.isZero() || refundWorkerInterval.isNegative()
				|| refundWorkerInterval.isZero() || refundBackoffInitial.isNegative() || refundBackoffInitial.isZero()
				|| refundBackoffCeiling.compareTo(refundBackoffInitial) < 0) {
			throw new IllegalArgumentException("invalid lhamalog.quota durations");
		}
		if (unavailableRetryAfter == null || unavailableRetryAfter.isNegative() || unavailableRetryAfter.isZero()) {
			throw new IllegalArgumentException("lhamalog.quota.unavailable-retry-after must be greater than 0");
		}
		if (unavailableMaxAttempts <= 0) {
			throw new IllegalArgumentException("lhamalog.quota.unavailable-max-attempts must be greater than 0");
		}
		if (unavailableAttemptTtl == null || unavailableAttemptTtl.isNegative() || unavailableAttemptTtl.isZero()) {
			throw new IllegalArgumentException("lhamalog.quota.unavailable-attempt-ttl must be greater than 0");
		}
	}

	public long unavailableRetryAfterSeconds() {
		return unavailableRetryAfter.toSeconds();
	}

	public long unavailableAttemptTtlSeconds() {
		return unavailableAttemptTtl.toSeconds();
	}
}
