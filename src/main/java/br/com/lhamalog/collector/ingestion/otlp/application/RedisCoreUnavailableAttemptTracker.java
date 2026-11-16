package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.quota.QuotaProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed Core-unavailable attempt counter (LLT-183 D4).
 *
 * <p>Key {@code lhamalog:quota:core-unavailable:{batchKey}} with TTL set atomically on first INCR.
 */
public final class RedisCoreUnavailableAttemptTracker implements CoreUnavailableAttemptTracker {

	private static final Logger log = LoggerFactory.getLogger(RedisCoreUnavailableAttemptTracker.class);

	static final String KEY_PREFIX = "lhamalog:quota:core-unavailable:";

	private static final DefaultRedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			  redis.call('EXPIRE', KEYS[1], ARGV[1])
			end
			return count
			""", Long.class);

	private final StringRedisTemplate redis;
	private final int maxAttempts;
	private final long attemptTtlSeconds;
	private final long retryAfterSeconds;
	private final IngestionObservability observability;

	public RedisCoreUnavailableAttemptTracker(StringRedisTemplate redis, QuotaProperties properties,
			IngestionObservability observability) {
		this.redis = redis;
		this.maxAttempts = properties.unavailableMaxAttempts();
		this.attemptTtlSeconds = properties.unavailableAttemptTtlSeconds();
		this.retryAfterSeconds = properties.unavailableRetryAfterSeconds();
		this.observability = observability == null ? IngestionObservability.NOOP : observability;
	}

	/** Test constructor with explicit thresholds. */
	RedisCoreUnavailableAttemptTracker(StringRedisTemplate redis, int maxAttempts, long attemptTtlSeconds,
			long retryAfterSeconds, IngestionObservability observability) {
		this.redis = redis;
		this.maxAttempts = maxAttempts;
		this.attemptTtlSeconds = attemptTtlSeconds;
		this.retryAfterSeconds = retryAfterSeconds;
		this.observability = observability == null ? IngestionObservability.NOOP : observability;
	}

	@Override
	public CoreUnavailableAttemptResult record(String batchKey) {
		if (batchKey == null || batchKey.isBlank()) {
			return new CoreUnavailableAttemptResult.StoreError();
		}
		String key = KEY_PREFIX + batchKey;
		try {
			Long count = redis.execute(INCR_EXPIRE, List.of(key), String.valueOf(attemptTtlSeconds));
			if (count == null) {
				return onStoreError();
			}
			if (count > maxAttempts) {
				return new CoreUnavailableAttemptResult.Exhausted(count);
			}
			return new CoreUnavailableAttemptResult.WithinBudget(count, retryAfterSeconds);
		}
		catch (RuntimeException ex) {
			log.warn(
					"event=quota.core_unavailable.attempt_store_unavailable | Redis Core-unavailable attempt counter failed | keyPrefix={} error={}",
					KEY_PREFIX, ex.toString());
			return onStoreError();
		}
	}

	@Override
	public void clear(String batchKey) {
		if (batchKey == null || batchKey.isBlank()) {
			return;
		}
		try {
			redis.delete(KEY_PREFIX + batchKey);
		}
		catch (RuntimeException ex) {
			log.warn(
					"event=quota.core_unavailable.attempt_clear_error | Could not clear Core-unavailable attempt key for batch | error={}",
					ex.toString());
		}
	}

	private CoreUnavailableAttemptResult onStoreError() {
		observability.quotaUnavailableAttemptStoreUnavailable();
		return new CoreUnavailableAttemptResult.StoreError();
	}

	static String key(String batchKey) {
		return KEY_PREFIX + batchKey;
	}
}
