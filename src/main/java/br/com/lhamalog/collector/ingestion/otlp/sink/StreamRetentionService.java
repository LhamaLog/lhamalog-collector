package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.logging.CorrelationId;
import br.com.lhamalog.collector.quota.RetentionCorrelation;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures OpenObserve stream {@code data_retention} matches the plan (LLT-185). Runs off the request path;
 * failures never refuse a batch already written.
 */
public final class StreamRetentionService {

	private static final Logger log = LoggerFactory.getLogger(StreamRetentionService.class);

	public static final String METRIC = "lhamalog.openobserve.retention.apply";

	private static final Duration CACHE_TTL = Duration.ofHours(1);
	private static final long CACHE_MAX = 10_000;
	private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(60);

	private final OpenObserveClient client;
	private final Clock clock;
	private final ExecutorService executor;
	private final Cache<CacheKey, Integer> applied;
	private final ConcurrentHashMap<CacheKey, Instant> failureBackoffUntil = new ConcurrentHashMap<>();
	private final MeterRegistry meters;

	public StreamRetentionService(OpenObserveClient client, Clock clock, MeterRegistry meters) {
		this(client, clock, meters, Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "oo-stream-retention");
			thread.setDaemon(true);
			return thread;
		}));
	}

	StreamRetentionService(OpenObserveClient client, Clock clock, MeterRegistry meters, ExecutorService executor) {
		this.client = Objects.requireNonNull(client, "client");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.meters = meters;
		this.executor = Objects.requireNonNull(executor, "executor");
		this.applied = Caffeine.newBuilder().maximumSize(CACHE_MAX).expireAfterWrite(CACHE_TTL).build();
	}

	/**
	 * Schedules retention apply using {@link RetentionCorrelation#current()} when known. No-op when unknown.
	 */
	public void ensureAfterIngestAsync(String streamType, String stream) {
		Integer days = RetentionCorrelation.current();
		if (days == null || days <= 0) {
			return;
		}
		ensureAsync(streamType, stream, days);
	}

	/** Schedules ensure for an explicit retention; used by tests and callers that already know N. */
	public void ensureAsync(String streamType, String stream, int retentionDays) {
		if (retentionDays <= 0 || stream == null || stream.isBlank()) {
			return;
		}
		String type = normalizeType(streamType);
		CacheKey key = new CacheKey(type, stream);
		Integer cached = applied.getIfPresent(key);
		if (cached != null && cached == retentionDays) {
			return;
		}
		Instant blockedUntil = failureBackoffUntil.get(key);
		if (blockedUntil != null && clock.instant().isBefore(blockedUntil)) {
			return;
		}
		executor.execute(() -> CorrelationId.propagate(() -> {
			apply(key, retentionDays);
			return null;
		}).get());
	}

	/** Synchronous apply for unit tests. */
	void applyNow(String streamType, String stream, int retentionDays) {
		apply(new CacheKey(normalizeType(streamType), stream), retentionDays);
	}

	private void apply(CacheKey key, int retentionDays) {
		Integer cached = applied.getIfPresent(key);
		if (cached != null && cached == retentionDays) {
			return;
		}
		Instant blockedUntil = failureBackoffUntil.get(key);
		if (blockedUntil != null && clock.instant().isBefore(blockedUntil)) {
			return;
		}
		try {
			int status = client.putStreamRetention(key.stream(), key.type(), retentionDays);
			if (status >= 200 && status < 300) {
				applied.put(key, retentionDays);
				failureBackoffUntil.remove(key);
				count("applied", key.type());
				return;
			}
			failureBackoffUntil.put(key, clock.instant().plus(FAILURE_BACKOFF));
			count("failed", key.type());
			log.warn(
					"event=openobserve.stream_retention.apply_failed | OpenObserve retention settings failed | stream={} type={} retentionDays={} status={}",
					key.stream(), key.type(), retentionDays, status);
		}
		catch (RuntimeException ex) {
			failureBackoffUntil.put(key, clock.instant().plus(FAILURE_BACKOFF));
			count("failed", key.type());
			log.warn(
					"event=openobserve.stream_retention.apply_failed | OpenObserve retention settings failed | stream={} type={} retentionDays={} status=unavailable",
					key.stream(), key.type(), retentionDays, ex);
		}
	}

	private void count(String outcome, String type) {
		if (meters == null) {
			return;
		}
		Counter.builder(METRIC).tag("outcome", outcome).tag("type", type).register(meters).increment();
	}

	private static String normalizeType(String streamType) {
		if (streamType == null || streamType.isBlank()) {
			return "logs";
		}
		return streamType.toLowerCase();
	}

	record CacheKey(String type, String stream) {
	}
}
