package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class UsageRedisStartupValidatorTest {

	@Test
	void rejectsRedisClusterWhenUsageIsEnabled() {
		StringRedisTemplate redis = redis(info("cluster_enabled", "1"), info("aof_enabled", "1"));

		assertThatThrownBy(() -> new UsageRedisStartupValidator(redis).afterSingletonsInstantiated())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Redis Cluster is not supported");
	}

	@Test
	void warnsButAllowsStartupWhenAofIsDisabled() {
		StringRedisTemplate redis = redis(info("cluster_enabled", "0"), info("aof_enabled", "0"));

		assertThatCode(() -> new UsageRedisStartupValidator(redis).afterSingletonsInstantiated())
			.doesNotThrowAnyException();
	}

	private static StringRedisTemplate redis(Properties cluster, Properties persistence) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		RedisConnection connection = mock(RedisConnection.class);
		RedisServerCommands server = mock(RedisServerCommands.class);
		when(connection.serverCommands()).thenReturn(server);
		when(server.info("cluster")).thenReturn(cluster);
		when(server.info("persistence")).thenReturn(persistence);
		doAnswer(invocation -> ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection))
			.when(redis).execute(any(RedisCallback.class));
		return redis;
	}

	private static Properties info(String key, String value) {
		Properties info = new Properties();
		info.setProperty(key, value);
		return info;
	}
}
