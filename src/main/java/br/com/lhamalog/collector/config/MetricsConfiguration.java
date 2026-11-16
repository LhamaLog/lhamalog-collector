package br.com.lhamalog.collector.config;

import br.com.lhamalog.collector.metrics.MetricsProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfiguration {

	private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
			"tenantid", "tenant_id", "tenant", "productid", "product_id", "token");

	@Bean
	MeterRegistryCustomizer<MeterRegistry> metricsCommonTags(
			@Value("${spring.application.name:lhamalog-collector}") String applicationName) {
		return registry -> registry.config()
				.commonTags("application", applicationName)
				.meterFilter(stripForbiddenTags());
	}

	/** Removes identity/secret tag keys (any casing) without dropping the meter. */
	static MeterFilter stripForbiddenTags() {
		return new MeterFilter() {
			@Override
			public Meter.Id map(Meter.Id id) {
				List<Tag> tags = id.getTags();
				List<Tag> kept = new ArrayList<>(tags.size());
				boolean removed = false;
				for (Tag tag : tags) {
					if (FORBIDDEN_TAG_KEYS.contains(tag.getKey().toLowerCase(Locale.ROOT))) {
						removed = true;
					}
					else {
						kept.add(tag);
					}
				}
				return removed ? id.replaceTags(kept) : id;
			}
		};
	}
}
