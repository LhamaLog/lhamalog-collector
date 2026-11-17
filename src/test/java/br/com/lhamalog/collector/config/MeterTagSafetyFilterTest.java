package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeterTagSafetyFilterTest {

	@Test
	void stripsForbiddenTagsIncludingCasingVariants() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		registry.config().meterFilter(MetricsConfiguration.stripForbiddenTags());

		Counter.builder("demo.counter")
				.tag("tenantId", "should-not-appear")
				.tag("product_id", "nope")
				.tag("TOKEN", "secret")
				.tag("signal", "logs")
				.register(registry)
				.increment();

		assertThat(registry.find("demo.counter").counter().getId().getTags())
				.extracting(tag -> tag.getKey())
				.containsExactly("signal")
				.doesNotContain("tenantId", "product_id", "TOKEN");
	}
}
