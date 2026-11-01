// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Startup validation of the receiver properties (T045, FR-024) and defaults of the new limits. */
class OtlpReceiverPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OtlpReceiverProperties.class)
	static class Config {

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void zeroMaxMessageSizeFailsAtStartupWithClearMessage() {
		runner.withPropertyValues("lhamalog.otlp.max-message-size=0B")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("lhamalog.otlp.max-message-size must be greater than 0"));
	}

	@Test
	void maxMessageSizeAboveIntFailsAtStartupWithClearMessage() {
		runner.withPropertyValues("lhamalog.otlp.max-message-size=3GB")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("must be at most 2147483647 bytes"));
	}

	@Test
	void defaultsAreSafe() {
		runner.run(context -> {
			OtlpReceiverProperties properties = context.getBean(OtlpReceiverProperties.class);
			assertThat(properties.maxMessageBytes()).isEqualTo(64 * 1024 * 1024);
			assertThat(properties.timestamp().toWindow().maxAge()).hasMinutes(290);
			assertThat(properties.tokenCache().negativeTtl()).hasSeconds(3);
			assertThat(properties.tokenCache().negativeMaxSize()).isEqualTo(1000);
			assertThat(properties.httpLimits().toLimits().maxConcurrentRequests()).isEqualTo(200);
		});
	}

	@Test
	void invalidNestedLimitsFailAtStartup() {
		runner.withPropertyValues("lhamalog.otlp.http-limits.max-concurrent-requests=0")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("max-concurrent-requests"));
		runner.withPropertyValues("lhamalog.otlp.timestamp.max-age=0s")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("lhamalog.otlp.token-cache.negative-max-size=0")
			.run(context -> assertThat(context).hasFailed());
	}

}
