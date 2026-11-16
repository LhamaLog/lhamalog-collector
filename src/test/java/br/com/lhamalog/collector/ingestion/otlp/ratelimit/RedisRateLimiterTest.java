package br.com.lhamalog.collector.ingestion.otlp.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.config.RateLimitProperties;
import br.com.lhamalog.collector.config.RateLimitProperties.StoreUnavailablePolicy;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** T010 / T017 / T041 — Lua fixed-window, violations block, provisional Redis-down fail-open. */
class RedisRateLimiterTest {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.10"))
			.withExposedPorts(6379);

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);

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
		var keys = redis.keys(RedisRateLimiter.KEY_PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void allowsTwentyRequestsThenLimitsOnTheTwentyFirstInSameSecond() {
		RedisRateLimiter limiter = limiter(20, 5, Duration.ofSeconds(60), Duration.ofSeconds(300));
		IntStream.rangeClosed(1, 20).forEach(i -> assertThat(limiter.check("tenant-a")).isEqualTo(Verdict.ALLOWED));
		assertThat(limiter.check("tenant-a")).isEqualTo(Verdict.LIMITED);
		String key = RedisRateLimiter.windowKey("tenant-a", CLOCK.instant().getEpochSecond());
		assertThat(redis.getExpire(key)).isBetween(1L, 2L);
		assertThat(redis.opsForValue().get(key)).isEqualTo("21");
	}

	@Test
	void fifthViolationWithinWindowBlocksAndBlockRequestsDoNotCountWindow() {
		RedisRateLimiter limiter = limiter(1, 5, Duration.ofSeconds(60), Duration.ofSeconds(300));
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.LIMITED); // violation 1
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.LIMITED); // 2
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.LIMITED); // 3
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.LIMITED); // 4
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.BLOCKED); // 5th → block
		String windowBefore = redis.opsForValue().get(RedisRateLimiter.windowKey("block-me", CLOCK.instant().getEpochSecond()));
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.BLOCKED);
		assertThat(limiter.check("block-me")).isEqualTo(Verdict.BLOCKED);
		String windowAfter = redis.opsForValue().get(RedisRateLimiter.windowKey("block-me", CLOCK.instant().getEpochSecond()));
		assertThat(windowAfter).isEqualTo(windowBefore);
		assertThat(redis.getExpire(RedisRateLimiter.blockKey("block-me"))).isBetween(1L, 300L);
	}

	@Test
	void blockExpiresAfterBlockDuration() throws InterruptedException {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
		RedisRateLimiter limiter = limiter(1, 2, Duration.ofSeconds(60), Duration.ofSeconds(1), clock);
		assertThat(limiter.check("expire")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("expire")).isEqualTo(Verdict.LIMITED);
		assertThat(limiter.check("expire")).isEqualTo(Verdict.BLOCKED);
		Thread.sleep(1100);
		clock.advanceSeconds(2); // new 1 s window key (fixed wall clock would reuse the old count)
		assertThat(limiter.check("expire")).isEqualTo(Verdict.ALLOWED);
	}

	@Test
	void violationsWindowExpiryResetsBlockThreshold() throws InterruptedException {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
		RedisRateLimiter limiter = limiter(1, 2, Duration.ofSeconds(1), Duration.ofSeconds(300), clock);
		assertThat(limiter.check("reset")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("reset")).isEqualTo(Verdict.LIMITED); // violation 1
		Thread.sleep(1100);
		clock.advanceSeconds(2);
		// Violation window expired: next over-limit starts a fresh violations counter (not blocked yet).
		assertThat(limiter.check("reset")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("reset")).isEqualTo(Verdict.LIMITED); // violation 1 again
		assertThat(redis.hasKey(RedisRateLimiter.blockKey("reset"))).isFalse();
		assertThat(limiter.check("reset")).isEqualTo(Verdict.BLOCKED); // violation 2 → block
	}

	@Test
	void logsAndTracesShareTheSameTenantCounter() {
		RedisRateLimiter limiter = limiter(2, 5, Duration.ofSeconds(60), Duration.ofSeconds(300));
		assertThat(limiter.check("shared")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("shared")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("shared")).isEqualTo(Verdict.LIMITED);
	}

	@Test
	void failOpenAllowsWhenRedisIsDownAndEmitsStoreUnavailableMetric() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability observability = new IngestionObservability(meters);
		RedisRateLimiter limiter = new RedisRateLimiter(downRedis(), props(20, 5, Duration.ofSeconds(60),
				Duration.ofSeconds(300), StoreUnavailablePolicy.FAIL_OPEN), observability, CLOCK);

		assertThat(limiter.check("tenant-x")).isEqualTo(Verdict.ALLOWED);
		assertThat(meters.find("lhamalog.ingestion.refused").tag("reason", "rate_limit_store_unavailable").counter()
				.count()).isEqualTo(1.0);
	}

	@Test
	void failClosedLimitsWhenRedisIsDown() {
		RedisRateLimiter limiter = new RedisRateLimiter(downRedis(),
				props(20, 5, Duration.ofSeconds(60), Duration.ofSeconds(300), StoreUnavailablePolicy.FAIL_CLOSED),
				IngestionObservability.NOOP, CLOCK);

		assertThat(limiter.check("tenant-x")).isEqualTo(Verdict.LIMITED);
	}

	@Test
	void disabledPropertyAlwaysAllows() {
		RedisRateLimiter limiter = new RedisRateLimiter(redis,
				new RateLimitProperties(false, 1, Duration.ofSeconds(5), 5, Duration.ofSeconds(60),
						Duration.ofSeconds(300), Duration.ofSeconds(300), StoreUnavailablePolicy.FAIL_OPEN),
				IngestionObservability.NOOP, CLOCK);
		assertThat(limiter.check("tenant-a")).isEqualTo(Verdict.ALLOWED);
		assertThat(limiter.check("tenant-a")).isEqualTo(Verdict.ALLOWED);
	}

	private static StringRedisTemplate downRedis() {
		LettuceClientConfiguration client = LettuceClientConfiguration.builder()
				.commandTimeout(Duration.ofMillis(200))
				.build();
		LettuceConnectionFactory down = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
				client);
		down.afterPropertiesSet();
		down.start();
		return new StringRedisTemplate(down);
	}

	private static RedisRateLimiter limiter(int limit, int violationsBeforeBlock, Duration violationWindow,
			Duration blockDuration) {
		return limiter(limit, violationsBeforeBlock, violationWindow, blockDuration, CLOCK);
	}

	private static RedisRateLimiter limiter(int limit, int violationsBeforeBlock, Duration violationWindow,
			Duration blockDuration, Clock clock) {
		return new RedisRateLimiter(redis, props(limit, violationsBeforeBlock, violationWindow, blockDuration,
				StoreUnavailablePolicy.FAIL_OPEN), IngestionObservability.NOOP, clock);
	}

	private static RateLimitProperties props(int limit, int violationsBeforeBlock, Duration violationWindow,
			Duration blockDuration, StoreUnavailablePolicy policy) {
		return new RateLimitProperties(true, limit, Duration.ofSeconds(5), violationsBeforeBlock, violationWindow,
				blockDuration, Duration.ofSeconds(300), policy);
	}

	/** Clock that tests can advance so Redis window keys rotate under a fixed wall clock. */
	private static final class MutableClock extends Clock {
		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advanceSeconds(long seconds) {
			instant = instant.plusSeconds(seconds);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
