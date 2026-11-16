package br.com.lhamalog.collector.config;

import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Validates Redis capabilities required by the usage acceptance protocol before
 * the application starts serving OTLP traffic.
 */
final class UsageRedisStartupValidator implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(UsageRedisStartupValidator.class);

	private final StringRedisTemplate redis;

	UsageRedisStartupValidator(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void afterSingletonsInstantiated() {
		Properties cluster = info("cluster");
		if ("1".equals(cluster.getProperty("cluster_enabled"))) {
			throw new IllegalStateException("Redis Cluster is not supported when lhamalog.usage.enabled=true");
		}

		Properties persistence = info("persistence");
		if ("0".equals(persistence.getProperty("aof_enabled"))) {
			log.warn("Redis AOF is disabled while lhamalog.usage.enabled=true; usage evidence may be lost after acknowledgement");
		}
	}

	private Properties info(String section) {
		return redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info(section));
	}
}
