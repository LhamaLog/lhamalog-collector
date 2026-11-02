// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagingPropertiesTest {

	@Test
	void defaultIsOneHundredThousand() {
		assertThat(new MessagingProperties(key -> null).maxLenFor("logs")).isEqualTo(100_000L);
	}

	@Test
	void environmentOverridesPerStream() {
		MessagingProperties properties = new MessagingProperties(
				Map.of("LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN", " 500 ")::get);
		assertThat(properties.maxLenFor("logs")).isEqualTo(500L);
		assertThat(properties.maxLenFor("traces")).isEqualTo(100_000L);
	}

	@Test
	void keyNormalizesTheDomain() {
		assertThat(MessagingProperties.maxLenKey("log-ingested.v1")).isEqualTo("LHAMALOG_MESSAGING_STREAM_LOG_INGESTED_V1_MAX_LEN");
	}

	@Test
	void invalidValueFailsFast() {
		assertThatThrownBy(() -> new MessagingProperties(key -> "abc").maxLenFor("logs"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MessagingProperties(key -> "0").maxLenFor("logs"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void outboundMessageRequiresTenant() {
		assertThatThrownBy(() -> new OutboundMessage<>("id", "body", Map.of(MetadataKeys.PRODUCT_ID, "p")))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
