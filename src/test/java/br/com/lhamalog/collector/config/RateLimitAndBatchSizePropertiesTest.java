package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.config.RateLimitProperties.StoreUnavailablePolicy;
import br.com.lhamalog.collector.quota.QuotaProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** T005 / T041 — defaults for max-batch-size, rate-limit (D5) and Core unavailable Retry-After (D4). */
class RateLimitAndBatchSizePropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ OtlpReceiverProperties.class, RateLimitProperties.class, QuotaProperties.class })
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void otlpDefaultsIncludeFiveMegabyteBatchCeiling() {
		runner.run(context -> {
			OtlpReceiverProperties otlp = context.getBean(OtlpReceiverProperties.class);
			assertThat(otlp.maxBatchBytes()).isEqualTo(5 * 1024 * 1024);
			assertThat(otlp.maxMessageBytes()).isEqualTo(64 * 1024 * 1024);
			assertThat(otlp.effectiveMaxBatchBytes()).isEqualTo(5 * 1024 * 1024);
		});
	}

	@Test
	void rateLimitDefaultsAreTwentyPerSecondRetryAfter5AndBlockSettings() {
		runner.run(context -> {
			RateLimitProperties rate = context.getBean(RateLimitProperties.class);
			assertThat(rate.enabled()).isTrue();
			assertThat(rate.requestsPerSecond()).isEqualTo(20);
			assertThat(rate.retryAfterSeconds()).isEqualTo(5);
			assertThat(rate.violationsBeforeBlock()).isEqualTo(5);
			assertThat(rate.violationWindowSeconds()).isEqualTo(60);
			assertThat(rate.blockDurationSeconds()).isEqualTo(300);
			assertThat(rate.blockRetryAfterSeconds()).isEqualTo(300);
			assertThat(rate.onStoreUnavailable()).isEqualTo(StoreUnavailablePolicy.FAIL_OPEN);
		});
	}

	@Test
	void quotaUnavailableRetryBudgetDefaults() {
		runner.run(context -> {
			QuotaProperties quota = context.getBean(QuotaProperties.class);
			assertThat(quota.unavailableRetryAfterSeconds()).isEqualTo(300);
			assertThat(quota.unavailableMaxAttempts()).isEqualTo(5);
			assertThat(quota.unavailableAttemptTtl()).isEqualTo(Duration.ofMinutes(30));
		});
	}

	@Test
	void rejectsNonPositiveRateLimitValues() {
		assertThatThrownBy(() -> props(0, Duration.ofSeconds(5), 5, Duration.ofSeconds(60), Duration.ofSeconds(300),
				Duration.ofSeconds(300))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props(20, Duration.ZERO, 5, Duration.ofSeconds(60), Duration.ofSeconds(300),
				Duration.ofSeconds(300))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props(20, Duration.ofSeconds(5), 0, Duration.ofSeconds(60), Duration.ofSeconds(300),
				Duration.ofSeconds(300))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props(20, Duration.ofSeconds(5), 5, Duration.ZERO, Duration.ofSeconds(300),
				Duration.ofSeconds(300))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props(20, Duration.ofSeconds(5), 5, Duration.ofSeconds(60), Duration.ZERO,
				Duration.ofSeconds(300))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props(20, Duration.ofSeconds(5), 5, Duration.ofSeconds(60), Duration.ofSeconds(300),
				Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositiveMaxBatchSize() {
		runner.withPropertyValues("lhamalog.otlp.max-batch-size=0B")
				.run(context -> assertThat(context).hasFailed()
						.getFailure()
						.rootCause()
						.hasMessageContaining("lhamalog.otlp.max-batch-size must be greater than 0"));
	}

	private static RateLimitProperties props(int rps, Duration retryAfter, int violations, Duration window,
			Duration blockDuration, Duration blockRetryAfter) {
		return new RateLimitProperties(true, rps, retryAfter, violations, window, blockDuration, blockRetryAfter,
				StoreUnavailablePolicy.FAIL_OPEN);
	}
}
