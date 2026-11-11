package br.com.lhamalog.collector.usage.core;

import br.com.lhamalog.collector.config.UsageProperties;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Delivers only accepted outbox items. The Mongo CAS is the fence: a recovered
 * or concurrent worker can only change an item from its currently observed state.
 */
public final class UsageOutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(UsageOutboxWorker.class);

	private final UsageOutboxRepository outbox;
	private final UsageFactsClient client;
	private final UsageProperties properties;
	private final Clock clock;
	private final String owner;

	private final UsageOutboxObservability observability;

	public UsageOutboxWorker(UsageOutboxRepository outbox, UsageFactsClient client, UsageProperties properties,
			Clock clock) {
		this(outbox, client, properties, clock, UUID.randomUUID().toString(), null);
	}

	UsageOutboxWorker(UsageOutboxRepository outbox, UsageFactsClient client, UsageProperties properties, Clock clock,
			String owner) {
		this(outbox, client, properties, clock, owner, null);
	}

	public UsageOutboxWorker(UsageOutboxRepository outbox, UsageFactsClient client, UsageProperties properties,
			Clock clock, UsageOutboxObservability observability) {
		this(outbox, client, properties, clock, UUID.randomUUID().toString(), observability);
	}

	private UsageOutboxWorker(UsageOutboxRepository outbox, UsageFactsClient client, UsageProperties properties,
			Clock clock, String owner, UsageOutboxObservability observability) {
		this.outbox = outbox;
		this.client = client;
		this.properties = properties;
		this.clock = clock;
		this.owner = owner;
		this.observability = observability == null ? UsageOutboxObservability.NOOP : observability;
		log.info("Usage outbox worker scheduled (interval={})", properties.workerInterval());
	}

	@Scheduled(fixedDelayString = "${lhamalog.usage.worker-interval:10s}")
	public void runOnce() {
		Instant now = clock.instant();
		var ready = outbox.findAcceptedReady(now);
		log.info("Usage outbox worker tick ready={}", ready.size());
		for (UsageOutboxItem candidate : ready) {
			deliver(candidate, now);
		}
	}

	private void deliver(UsageOutboxItem candidate, Instant now) {
		UsageOutboxItem leased;
		try {
			leased = candidate.lease(owner, now, now.plus(properties.lease()));
		}
		catch (IllegalStateException exception) {
			log.warn("Usage fact {} not available for lease ({})", candidate.id(), exception.getMessage());
			return;
		}
		if (!outbox.compareAndSet(candidate, leased)) {
			log.warn("Usage fact {} lease CAS did not match the stored item", candidate.id());
			return;
		}
		try {
			int status = client.send(leased.fact());
			if (status >= 200 && status < 300) {
				if (outbox.compareAndSet(leased, leased.sync(clock.instant()))) {
					observability.transition(UsageOutboxState.SYNCED);
				}
				log.info("Usage fact delivered to Core status={} domain={} records={} bytes={}", status,
						leased.fact().domain(), leased.fact().acceptedRecordCount(), leased.fact().acceptedBytes());
			}
			else if (status >= 400 && status < 500 && status != 429) {
				suspend(leased, "HTTP_" + status);
			}
			else {
				retry(leased, "HTTP_" + status);
			}
		}
		catch (IOException ex) {
			retry(leased, "NETWORK");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			retry(leased, "INTERRUPTED");
		}
		catch (RuntimeException ex) {
			log.warn("Usage fact {} delivery failed unexpectedly", leased.id(), ex);
			retry(leased, "UNEXPECTED");
		}
	}

	static final Instant BSON_DATE_MAX = Instant.ofEpochMilli(253402300799999L);

	private void retry(UsageOutboxItem item, String code) {
		Instant now = clock.instant();
		outbox.compareAndSet(item, item.retry(nextRetryAt(item, now, properties), code));
		observability.retry(item);
		log.warn("Usage fact {} delivery deferred ({})", item.id(), code);
	}

	static Instant nextRetryAt(UsageOutboxItem item, Instant now, UsageProperties properties) {
		long base = Math.max(1L, properties.backoffInitial().toMillis());
		long ceiling = Math.max(base, properties.backoffCeiling().toMillis());
		int shift = Math.min(20, Math.max(0, item.attemptCount() - 1));
		long delay = base;
		for (int step = 0; step < shift && delay <= ceiling / 2L; step++) {
			delay *= 2L;
		}
		delay = Math.min(ceiling, delay);
		long jitter = Math.floorMod(item.id().hashCode(), Math.max(1L, Math.min(delay / 4L + 1L, 1_000L)));
		try {
			Instant next = now.plusMillis(delay + jitter);
			return next.isAfter(BSON_DATE_MAX) ? BSON_DATE_MAX : next;
		}
		catch (ArithmeticException exception) {
			return BSON_DATE_MAX;
		}
	}

	private void suspend(UsageOutboxItem item, String code) {
		outbox.compareAndSet(item, item.suspend(code));
		observability.suspended(item);
		log.warn("Usage fact {} delivery suspended ({})", item.id(), code);
	}
}
