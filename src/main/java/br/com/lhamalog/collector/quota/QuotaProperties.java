package br.com.lhamalog.collector.quota;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Synchronous ingress quota protection. Disabled by default to preserve spec005 behaviour. */
@ConfigurationProperties("lhamalog.quota")
public record QuotaProperties(@DefaultValue("false") boolean enabled, @DefaultValue("5s") Duration timeout,
		@DefaultValue("10s") Duration refundWorkerInterval, @DefaultValue("250ms") Duration refundBackoffInitial,
		@DefaultValue("30s") Duration refundBackoffCeiling) {
	public QuotaProperties {
		if (timeout == null || timeout.isNegative() || timeout.isZero() || refundWorkerInterval.isNegative()
				|| refundWorkerInterval.isZero() || refundBackoffInitial.isNegative() || refundBackoffInitial.isZero()
				|| refundBackoffCeiling.compareTo(refundBackoffInitial) < 0) {
			throw new IllegalArgumentException("invalid lhamalog.quota durations");
		}
	}
}
