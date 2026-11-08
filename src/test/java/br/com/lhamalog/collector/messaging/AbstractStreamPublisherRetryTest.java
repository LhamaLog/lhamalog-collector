// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for internal retry, backoff and DLQ success/failure (LLT-184). */
class AbstractStreamPublisherRetryTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final MessagingProperties PROPERTIES = MessagingProperties.defaults();

	static final class SamplePublisher extends AbstractStreamPublisher<Map<String, Object>> {

		SamplePublisher(StringRedisTemplate redis, PublishSleeper sleeper) {
			super(redis, JSON, PROPERTIES, CLOCK, sleeper);
		}

		@Override
		protected String domain() {
			return "sample";
		}

		@Override
		protected String origin() {
			return "collector";
		}

		@Override
		protected String event() {
			return "sample.happened";
		}

		@Override
		protected int schemaVersion() {
			return 1;
		}

	}

	private static OutboundMessage<Map<String, Object>> message() {
		return new OutboundMessage<>("id-1", Map.of("n", 1),
				Map.of(MetadataKeys.TENANT_ID, "tenant-1", MetadataKeys.PRODUCT_ID, "product-a"));
	}

	private static DataAccessException boom(String detail) {
		return new DataAccessException(detail) {
		};
	}

	@Test
	void succeedsOnSecondAttemptWithoutDlq() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		AtomicInteger calls = new AtomicInteger();
		when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
			if (calls.incrementAndGet() == 1) {
				throw boom("transient");
			}
			return List.of("1-0");
		});
		AtomicLong slept = new AtomicLong();
		SamplePublisher publisher = new SamplePublisher(redis, slept::addAndGet);

		List<MessageEnvelope> envelopes = publisher.publish(List.of(message()));

		assertThat(envelopes).hasSize(1);
		assertThat(envelopes.getFirst().idempotencyId()).isEqualTo("id-1");
		assertThat(calls.get()).isEqualTo(2);
		assertThat(slept.get()).isEqualTo(100L);
		verify(redis, times(2)).executePipelined(any(RedisCallback.class));
	}

	@Test
	void exhaustedRetriesWriteDlqAndReturnSuccess() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		AtomicInteger calls = new AtomicInteger();
		List<String> keys = new ArrayList<>();
		when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
			int n = calls.incrementAndGet();
			if (n <= 3) {
				throw boom("still down");
			}
			keys.add("dlq");
			return List.of("5-0");
		});
		AtomicLong slept = new AtomicLong();
		SamplePublisher publisher = new SamplePublisher(redis, slept::addAndGet);

		List<MessageEnvelope> envelopes = publisher.publish(List.of(message()));

		assertThat(envelopes).hasSize(1);
		assertThat(calls.get()).isEqualTo(4);
		assertThat(keys).containsExactly("dlq");
		assertThat(slept.get()).isEqualTo(300L);
		assertThat(publisher.dlqStream()).isEqualTo("lhamalog:stream:sample:dlq");
	}

	@Test
	void dlqFailureKeepsMessagePublishException() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.executePipelined(any(RedisCallback.class))).thenThrow(boom("everywhere down"));
		SamplePublisher publisher = new SamplePublisher(redis, ms -> {
		});

		assertThatThrownBy(() -> publisher.publish(List.of(message()))).isInstanceOf(MessagePublishException.class)
			.hasMessageContaining("lhamalog:stream:sample")
			.hasMessageContaining("dlq");
		verify(redis, times(4)).executePipelined(any(RedisCallback.class));
	}

	@Test
	void formatErrorOmitsBlankMessageAndRedactsSecrets() {
		assertThat(AbstractStreamPublisher.formatError(null)).isEqualTo("unknown");
		assertThat(AbstractStreamPublisher.formatError(new RuntimeException())).isEqualTo("RuntimeException");
		String sensitiveValue = "sensitive-value";
		String formatted = AbstractStreamPublisher.formatError(new RuntimeException(
				"password=" + sensitiveValue + " passwd:" + sensitiveValue + " senha=\"" + sensitiveValue
						+ "\" secret='" + sensitiveValue + "' token=" + sensitiveValue + " apikey:" + sensitiveValue
						+ " api_key=" + sensitiveValue + " authorization: Bearer " + sensitiveValue
						+ " redis://user:" + sensitiveValue + "@redis:6379"));

		assertThat(formatted).isEqualTo("RuntimeException: password=*** passwd:*** senha=*** secret=*** token=*** "
				+ "apikey:*** api_key=*** authorization: *** redis://user:***@redis:6379");
		assertThat(formatted).doesNotContain(sensitiveValue);
		assertThat(AbstractStreamPublisher.formatError(
				new RuntimeException("{\"token\":\"" + sensitiveValue + "\",\"api_key\":\"" + sensitiveValue + "\"}")))
			.isEqualTo("RuntimeException: {\"token\":***,\"api_key\":***}");
		assertThat(AbstractStreamPublisher.formatError(new RuntimeException("Bearer " + sensitiveValue)))
			.isEqualTo("RuntimeException: Bearer ***");
	}

	@Test
	@SuppressWarnings("unchecked")
	void dlqPipelineReceivesFailureFields() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		AtomicInteger calls = new AtomicInteger();
		ArgumentCaptor<RedisCallback<Object>> captor = ArgumentCaptor.forClass(RedisCallback.class);
		when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
			if (calls.incrementAndGet() <= 3) {
				throw boom("main down");
			}
			return List.of("9-0");
		});
		new SamplePublisher(redis, ms -> {
		}).publish(List.of(message()));

		verify(redis, times(4)).executePipelined(captor.capture());
		assertThat(captor.getAllValues()).hasSize(4);
	}

}