package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class UsageEnabledConditionTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(Sample.class);

	@Test
	void createsTheConditionalBeanWhenUsageIsEnabled() {
		runner.withPropertyValues("lhamalog.usage.enabled=true")
				.run(context -> assertThat(context).hasBean("usageMarker"));
	}

	@Test
	void skipsTheConditionalBeanWhenUsageIsDisabled() {
		runner.withPropertyValues("lhamalog.usage.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean("usageMarker"));
	}

	@Configuration
	static class Sample {
		@Bean
		@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
		String usageMarker() {
			return "enabled";
		}
	}
}
