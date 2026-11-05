// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Publisher against a real Redis (Testcontainers), including MAXLEN ~ trimming and Redis down. */
class StreamPublisherRedisTest {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.10"))
		.withExposedPorts(6379);

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static LettuceConnectionFactory factory;

	private static StringRedisTemplate redis;

	static final class SamplePublisher extends AbstractStreamPublisher<Map<String, Object>> {

		SamplePublisher(StringRedisTemplate redis, MessagingProperties properties) {
			super(redis, JSON, properties, CLOCK);
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

	@BeforeAll
	static void start() {
		REDIS.start();
		factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
		factory.afterPropertiesSet();
		factory.start();
		redis = new StringRedisTemplate(factory);
	}

	@AfterAll
	static void stop() {
		factory.destroy();
		REDIS.stop();
	}

	@BeforeEach
	void clean() {
		redis.delete(StreamNames.stream("sample"));
	}

	private static OutboundMessage<Map<String, Object>> message(int n) {
		return new OutboundMessage<>("id-" + n, Map.of("n", n),
				Map.of(MetadataKeys.TENANT_ID, "tenant-1", MetadataKeys.PRODUCT_ID, "product-a"));
	}

	@Test
	void writesTheEnvelopeAsStreamFields() throws Exception {
		new SamplePublisher(redis, new MessagingProperties(key -> null)).publish(List.of(message(1)));

		List<MapRecord<String, Object, Object>> entries = redis.opsForStream()
			.range("lhamalog:stream:sample", Range.unbounded());
		assertThat(entries).hasSize(1);
		Map<Object, Object> fields = entries.getFirst().getValue();
		assertThat(fields).containsEntry("idempotencyId", "id-1")
			.containsEntry("event", "sample.happened")
			.containsEntry("origin", "collector")
			.containsEntry("schemaVersion", "1")
			.containsEntry("publishedAt", "2026-11-01T12:00:00Z");
		assertThat(JSON.readTree((String) fields.get("metadata")).path("tenantId").asString()).isEqualTo("tenant-1");
		assertThat(JSON.readTree((String) fields.get("body")).path("n").asInt()).isEqualTo(1);
	}

	@Test
	void trimsApproximatelyToTheConfiguredLimit() {
		SamplePublisher publisher = new SamplePublisher(redis,
				new MessagingProperties(Map.of("LHAMALOG_MESSAGING_STREAM_SAMPLE_MAX_LEN", "10")::get));
		assertThat(publisher.maxLen()).isEqualTo(10L);
		for (int round = 0; round < 10; round++) {
			int base = round * 100;
			publisher.publish(IntStream.range(base, base + 100).mapToObj(StreamPublisherRedisTest::message).toList());
		}
		// "~" trims whole nodes (100 entries by default), so the length stays far below the 1000 written.
		assertThat(redis.opsForStream().size("lhamalog:stream:sample")).isLessThan(300L);
	}

	@Test
	void redisDownIsMessagePublishException() {
		LettuceConnectionFactory down = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
				LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
		down.afterPropertiesSet();
		down.start();
		try {
			SamplePublisher publisher = new SamplePublisher(new StringRedisTemplate(down), new MessagingProperties(key -> null));
			assertThatThrownBy(() -> publisher.publish(List.of(message(1)))).isInstanceOf(MessagePublishException.class);
		}
		finally {
			down.destroy();
		}
	}

}
