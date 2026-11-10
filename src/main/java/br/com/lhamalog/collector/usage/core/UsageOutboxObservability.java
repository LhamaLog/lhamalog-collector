package br.com.lhamalog.collector.usage.core;

import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records operational state without attaching OTLP payloads, identities, or
 * authentication material to logs or metric tags.
 */
public final class UsageOutboxObservability {

	private static final Logger log = LoggerFactory.getLogger(UsageOutboxObservability.class);

	private final MeterRegistry meters;

	private final AtomicLong attempts = new AtomicLong();

	private final AtomicLong pendingAgeSeconds = new AtomicLong();

	private final AtomicLong evidenceTtlSeconds = new AtomicLong();

	public UsageOutboxObservability(MeterRegistry meters) {
		this.meters = meters;
		meters.gauge("lhamalog.usage.outbox.attempts", attempts);
		meters.gauge("lhamalog.usage.outbox.pending.age.seconds", pendingAgeSeconds);
		meters.gauge("lhamalog.usage.outbox.evidence.ttl.seconds", evidenceTtlSeconds);
	}

	public void transition(UsageOutboxState state) {
		Counter.builder("lhamalog.usage.outbox.transitions").tag("state", state.name()).register(meters).increment();
	}

	public void retry(UsageOutboxItem item) {
		Counter.builder("lhamalog.usage.outbox.retries").register(meters).increment();
		attempts.set(item.attemptCount());
	}

	public void pendingAge(UsageOutboxItem item, Instant now, Duration grace) {
		double age = Math.max(0, Duration.between(item.createdAt(), now).toSeconds());
		pendingAgeSeconds.set((long) age);
		if (age > grace.toSeconds()) {
			Counter.builder("lhamalog.usage.outbox.pending.over_grace").register(meters).increment();
			log.warn("Usage outbox pending item exceeded liveness grace");
		}
	}

	public void ambiguous() {
		Counter.builder("lhamalog.usage.outbox.ambiguous").register(meters).increment();
		log.error("Usage outbox contains ambiguous Redis acceptance evidence");
	}

	public void evidenceExpiryConfigured(Duration ttl) {
		evidenceTtlSeconds.set(ttl.toSeconds());
	}
}
