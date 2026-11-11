package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuotaPropertiesTest {

	@Test
	void defaultsKeepQuotaDisabledAndE2eFaultInjectionOff() {
		QuotaProperties quota = new QuotaProperties(false, Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofMillis(250), Duration.ofSeconds(30));
		QuotaE2eProperties e2e = new QuotaE2eProperties(false);

		assertThat(quota.enabled()).isFalse();
		assertThat(quota.timeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(quota.refundWorkerInterval()).isEqualTo(Duration.ofSeconds(10));
		assertThat(quota.refundBackoffInitial()).isEqualTo(Duration.ofMillis(250));
		assertThat(quota.refundBackoffCeiling()).isEqualTo(Duration.ofSeconds(30));
		assertThat(e2e.enabled()).isFalse();
	}

	@Test
	void rejectsInvalidDurationsAndBackoffCeilingBelowInitial() {
		assertThatThrownBy(() -> new QuotaProperties(true, Duration.ZERO, Duration.ofSeconds(10), Duration.ofMillis(250),
				Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QuotaProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofSeconds(30), Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new QuotaProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofMillis(250), Duration.ofSeconds(30))).doesNotThrowAnyException();
	}
}
