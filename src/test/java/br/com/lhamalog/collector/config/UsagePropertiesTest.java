package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UsagePropertiesTest {

	@Test
	void rejectsAGracePeriodThatDoesNotExceedTheLease() {
		assertThatThrownBy(() -> new UsageProperties(true, new UsageProperties.Core("http://localhost:8081"),
				Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofDays(7),
				Duration.ofMillis(250), Duration.ofSeconds(30)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lhamalog.usage");
	}

	@Test
	void acceptsDefaultsWhereGraceExceedsTheLeaseAndBackoffHasACeiling() {
		assertThatCode(() -> new UsageProperties(true, new UsageProperties.Core("http://localhost:8081"),
				Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofDays(7),
				Duration.ofMillis(250), Duration.ofSeconds(30))).doesNotThrowAnyException();
	}
}
