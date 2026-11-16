package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Redis Lua INCR+EXPIRE for Core-unavailable attempt budget (T043). */
class RedisCoreUnavailableAttemptTrackerTest {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.10"))
			.withExposedPorts(6379);

	private static LettuceConnectionFactory factory;
	private static StringRedisTemplate redis;

	@BeforeAll
	static void start() {
		REDIS.start();
		factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
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
		var keys = redis.keys(RedisCoreUnavailableAttemptTracker.KEY_PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void incrementsWithinBudgetThenExhaustsAndClearsOnSuccess() {
		RedisCoreUnavailableAttemptTracker tracker = new RedisCoreUnavailableAttemptTracker(redis, 5, 1800, 300,
				IngestionObservability.NOOP);
		IntStream.rangeClosed(1, 5).forEach(i -> {
			CoreUnavailableAttemptResult result = tracker.record("quota:abc");
			assertThat(result).isEqualTo(new CoreUnavailableAttemptResult.WithinBudget(i, 300));
		});
		assertThat(tracker.record("quota:abc")).isEqualTo(new CoreUnavailableAttemptResult.Exhausted(6));
		assertThat(redis.getExpire(RedisCoreUnavailableAttemptTracker.key("quota:abc"))).isBetween(1L, 1800L);
		tracker.clear("quota:abc");
		assertThat(redis.hasKey(RedisCoreUnavailableAttemptTracker.key("quota:abc"))).isFalse();
		assertThat(tracker.record("quota:abc")).isEqualTo(new CoreUnavailableAttemptResult.WithinBudget(1, 300));
	}

	@Test
	void redisErrorReturnsStoreErrorAndMetric() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability observability = new IngestionObservability(meters);
		LettuceClientConfiguration client = LettuceClientConfiguration.builder()
				.commandTimeout(Duration.ofMillis(200))
				.build();
		LettuceConnectionFactory down = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
				client);
		down.afterPropertiesSet();
		down.start();
		RedisCoreUnavailableAttemptTracker tracker = new RedisCoreUnavailableAttemptTracker(
				new StringRedisTemplate(down), 5, 1800, 300, observability);

		assertThat(tracker.record("quota:x")).isInstanceOf(CoreUnavailableAttemptResult.StoreError.class);
		assertThat(meters.find("lhamalog.ingestion.refused")
				.tag("reason", "quota_unavailable_attempt_store_unavailable").counter().count()).isEqualTo(1.0);
	}
}
