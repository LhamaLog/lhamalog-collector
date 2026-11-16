package br.com.lhamalog.collector.ingestion.otlp.ratelimit;

import br.com.lhamalog.collector.config.RateLimitProperties;
import br.com.lhamalog.collector.config.RateLimitProperties.StoreUnavailablePolicy;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Fixed one-second window rate limiter with violation block (LLT-183 D5).
 *
 * <p>Atomic Lua: check block → window INCR → on violation INCR violations with window EXPIRE → set
 * block key when threshold reached. Requests during a block are not counted in the 1 s window. The
 * 5th violation inside the violation window triggers the block (that request already gets block
 * Retry-After).
 *
 * <p>Keys: {@code lhamalog:ratelimit:{tenantId}:{epochSecond}} (TTL 2 s);
 * {@code lhamalog:ratelimit:violations:{tenantId}}; {@code lhamalog:ratelimit:block:{tenantId}}.
 */
public final class RedisRateLimiter implements TenantRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	static final String KEY_PREFIX = "lhamalog:ratelimit:";
	static final String VIOLATIONS_PREFIX = KEY_PREFIX + "violations:";
	static final String BLOCK_PREFIX = KEY_PREFIX + "block:";
	static final int KEY_TTL_SECONDS = 2;

	/** 0 = allowed, 1 = limited, 2 = blocked. */
	private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[3]) == 1 then
			  return 2
			end
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			  redis.call('EXPIRE', KEYS[1], ARGV[1])
			end
			if count > tonumber(ARGV[2]) then
			  local violations = redis.call('INCR', KEYS[2])
			  if violations == 1 then
			    redis.call('EXPIRE', KEYS[2], ARGV[3])
			  end
			  if violations >= tonumber(ARGV[4]) then
			    redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
			    return 2
			  end
			  return 1
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;
	private final RateLimitProperties properties;
	private final IngestionObservability observability;
	private final Clock clock;

	public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties,
			IngestionObservability observability) {
		this(redis, properties, observability, Clock.systemUTC());
	}

	public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties,
			IngestionObservability observability, Clock clock) {
		this.redis = redis;
		this.properties = properties;
		this.observability = observability == null ? IngestionObservability.NOOP : observability;
		this.clock = clock;
	}

	@Override
	public Verdict check(String tenantId) {
		if (!properties.enabled()) {
			return Verdict.ALLOWED;
		}
		long epochSecond = clock.instant().getEpochSecond();
		String windowKey = windowKey(tenantId, epochSecond);
		String violationsKey = VIOLATIONS_PREFIX + tenantId;
		String blockKey = BLOCK_PREFIX + tenantId;
		try {
			Long code = redis.execute(CHECK, List.of(windowKey, violationsKey, blockKey),
					String.valueOf(KEY_TTL_SECONDS), String.valueOf(properties.requestsPerSecond()),
					String.valueOf(properties.violationWindowSeconds()),
					String.valueOf(properties.violationsBeforeBlock()),
					String.valueOf(properties.blockDurationSeconds()));
			if (code == null) {
				return onStoreUnavailable();
			}
			if (code == 2L) {
				return Verdict.BLOCKED;
			}
			if (code == 1L) {
				return Verdict.LIMITED;
			}
			return Verdict.ALLOWED;
		}
		catch (RuntimeException ex) {
			log.warn("Redis rate-limit check failed for key pattern {}*: {}", KEY_PREFIX, ex.toString());
			return onStoreUnavailable();
		}
	}

	private Verdict onStoreUnavailable() {
		observability.rateLimitStoreUnavailable();
		if (properties.onStoreUnavailable() == StoreUnavailablePolicy.FAIL_CLOSED) {
			return Verdict.LIMITED;
		}
		return Verdict.ALLOWED;
	}

	/** Visible for tests: builds the Redis window key for a tenant at a given epoch second. */
	static String windowKey(String tenantId, long epochSecond) {
		return KEY_PREFIX + tenantId + ":" + epochSecond;
	}

	/** @deprecated use {@link #windowKey(String, long)} */
	static String key(String tenantId, long epochSecond) {
		return windowKey(tenantId, epochSecond);
	}

	static String violationsKey(String tenantId) {
		return VIOLATIONS_PREFIX + tenantId;
	}

	static String blockKey(String tenantId) {
		return BLOCK_PREFIX + tenantId;
	}
}
