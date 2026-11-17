package br.com.lhamalog.collector.metrics;

import br.com.lhamalog.collector.messaging.StreamNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caches Redis stream XLEN for main and DLQ streams. Does not hit Redis on every Prometheus scrape.
 * <p>No {@code @ConditionalOnBean}: on a scanned component it is evaluated before Redis auto-configuration
 * and would silently drop the gauges. The Collector always has a {@link StringRedisTemplate}.
 */
@Component
public class QueueSizeMetricsRefresher {

	private static final Logger log = LoggerFactory.getLogger(QueueSizeMetricsRefresher.class);

	private static final String[] SIGNALS = { "logs", "traces" };

	private final StringRedisTemplate redis;

	private final MetricsProperties properties;

	private final Sample[] samples;

	public QueueSizeMetricsRefresher(StringRedisTemplate redis, MeterRegistry meters, MetricsProperties properties) {
		this.redis = redis;
		this.properties = properties;
		this.samples = new Sample[SIGNALS.length];
		for (int i = 0; i < SIGNALS.length; i++) {
			String signal = SIGNALS[i];
			Sample sample = new Sample();
			samples[i] = sample;
			Gauge.builder("lhamalog.queue.size", sample, Sample::queueSize).tag("signal", signal).register(meters);
			Gauge.builder("lhamalog.dlq.size", sample, Sample::dlqSize).tag("signal", signal).register(meters);
		}
	}

	@Scheduled(fixedDelayString = "${lhamalog.metrics.queue-size-refresh:15s}")
	public void refresh() {
		for (int i = 0; i < SIGNALS.length; i++) {
			refreshSignal(SIGNALS[i], samples[i]);
		}
	}

	private void refreshSignal(String signal, Sample sample) {
		try {
			Long queue = redis.opsForStream().size(StreamNames.stream(signal));
			Long dlq = redis.opsForStream().size(StreamNames.dlq(signal));
			sample.set(queue == null ? 0.0d : queue.doubleValue(), dlq == null ? 0.0d : dlq.doubleValue());
		}
		catch (RuntimeException ex) {
			if (sample.shouldWarn(properties.queueSizeWarnInterval().toMillis())) {
				log.warn(
						"event=metrics.queue_size.refresh_failed | Redis stream depth refresh failed | signal={} error={}",
						signal, ex.toString());
			}
		}
	}

	private static final class Sample {

		private final AtomicLong lastWarnAt = new AtomicLong();

		private volatile double queueSize = Double.NaN;

		private volatile double dlqSize = Double.NaN;

		void set(double queueSize, double dlqSize) {
			this.queueSize = queueSize;
			this.dlqSize = dlqSize;
		}

		double queueSize() {
			return queueSize;
		}

		double dlqSize() {
			return dlqSize;
		}

		boolean shouldWarn(long intervalMs) {
			long now = System.currentTimeMillis();
			long previous = lastWarnAt.get();
			if (now - previous < intervalMs) {
				return false;
			}
			return lastWarnAt.compareAndSet(previous, now);
		}
	}
}
