// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingPropertiesTest {

	@Test
	void defaultIsOneHundredThousand() {
		assertThat(MessagingProperties.defaults().maxLenFor("logs")).isEqualTo(100_000L);
		assertThat(MessagingProperties.defaults().maxLenFor("traces")).isEqualTo(100_000L);
		assertThat(MessagingProperties.defaults().maxLenFor("sample")).isEqualTo(100_000L);
	}

	@Test
	void perStreamOverrides() {
		MessagingProperties properties = new MessagingProperties(
				new MessagingProperties.Streams(100_000L, 500L, 100_000L, 100_000L, 100_000L),
				MessagingProperties.Publish.defaults());
		assertThat(properties.maxLenFor("logs")).isEqualTo(500L);
		assertThat(properties.maxLenFor("traces")).isEqualTo(100_000L);
	}

	@Test
	void invalidStreamLimitFailsFast() {
		assertThatThrownBy(() -> new MessagingProperties(
				new MessagingProperties.Streams(100_000L, 0L, 100_000L, 100_000L, 100_000L),
				MessagingProperties.Publish.defaults())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void outboundMessageRequiresTenant() {
		assertThatThrownBy(() -> new OutboundMessage<>("id", "body", Map.of(MetadataKeys.PRODUCT_ID, "p")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void publishRetryDefaults() {
		MessagingProperties properties = MessagingProperties.defaults();
		assertThat(properties.publishMaxAttempts()).isEqualTo(3);
		assertThat(properties.publishBackoffInitialMs()).isEqualTo(100L);
		assertThat(properties.publishBackoffMultiplier()).isEqualTo(2.0d);
		assertThat(properties.publishBackoffCeilingMs()).isEqualTo(2_000L);
		assertThat(properties.maxLenFor("logs:dlq")).isEqualTo(100_000L);
	}

	@Test
	void publishRetryOverrides() {
		MessagingProperties properties = new MessagingProperties(
				new MessagingProperties.Streams(100_000L, 100_000L, 100_000L, 10L, 100_000L),
				new MessagingProperties.Publish(5, 50L, 3.0d, 1_500L));
		assertThat(properties.publishMaxAttempts()).isEqualTo(5);
		assertThat(properties.publishBackoffInitialMs()).isEqualTo(50L);
		assertThat(properties.publishBackoffMultiplier()).isEqualTo(3.0d);
		assertThat(properties.publishBackoffCeilingMs()).isEqualTo(1_500L);
		assertThat(properties.maxLenFor("logs:dlq")).isEqualTo(10L);
	}

	@Test
	void invalidPublishRetryFailsFast() {
		assertThatThrownBy(() -> new MessagingProperties(MessagingProperties.Streams.defaults(),
				new MessagingProperties.Publish(0, 100L, 2.0d, 2_000L))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MessagingProperties(MessagingProperties.Streams.defaults(),
				new MessagingProperties.Publish(3, 100L, 0.5d, 2_000L))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void streamNamesDlq() {
		assertThat(StreamNames.dlq("logs")).isEqualTo("lhamalog:stream:logs:dlq");
		assertThat(StreamNames.dlq("traces")).isEqualTo("lhamalog:stream:traces:dlq");
	}

}