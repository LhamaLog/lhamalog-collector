package br.com.lhamalog.collector.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lhamalog.usage")
public record UsageProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue Core core,
		@DefaultValue("30s") Duration lease,
		@DefaultValue("10s") Duration workerInterval,
		@DefaultValue("2m") Duration grace,
		@DefaultValue("7d") Duration deduplicationTtl,
		@DefaultValue("250ms") Duration backoffInitial,
		@DefaultValue("30s") Duration backoffCeiling) {

	public record Core(@DefaultValue("http://localhost:8081") String url) {
	}

	public UsageProperties {
		if (lease.isNegative() || lease.isZero() || workerInterval.isNegative() || workerInterval.isZero()
				|| grace.compareTo(lease) <= 0 || deduplicationTtl.isNegative() || deduplicationTtl.isZero()
				|| backoffInitial.isNegative() || backoffInitial.isZero() || backoffCeiling.compareTo(backoffInitial) < 0) {
			throw new IllegalArgumentException("invalid lhamalog.usage durations");
		}
	}
}
