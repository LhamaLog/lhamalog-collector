package br.com.lhamalog.collector.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Operational metrics settings (LLT-187), including Redis stream depth refresh. */
@ConfigurationProperties("lhamalog.metrics")
public record MetricsProperties(
		@DefaultValue("15s") Duration queueSizeRefresh,
		@DefaultValue("60s") Duration queueSizeWarnInterval) {

	public MetricsProperties {
		if (queueSizeRefresh == null || queueSizeRefresh.isZero() || queueSizeRefresh.isNegative()) {
			throw new IllegalArgumentException("lhamalog.metrics.queue-size-refresh must be positive");
		}
		if (queueSizeWarnInterval == null || queueSizeWarnInterval.isZero() || queueSizeWarnInterval.isNegative()) {
			throw new IllegalArgumentException("lhamalog.metrics.queue-size-warn-interval must be positive");
		}
	}
}
