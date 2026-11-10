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
		this.observability = observability;
	}

	@Scheduled(fixedDelayString = "${lhamalog.usage.worker-interval:10s}")
	public void runOnce() {
		Instant now = clock.instant();
		for (UsageOutboxItem candidate : outbox.findAcceptedReady(now)) {
			deliver(candidate, now);
		}
	}

	private void deliver(UsageOutboxItem candidate, Instant now) {
		UsageOutboxItem leased;
		try {
			leased = candidate.lease(owner, now, now.plus(properties.lease()));
		}
		catch (IllegalStateException ignored) {
			return;
		}
		if (!outbox.compareAndSet(candidate.id(), UsageOutboxState.ACCEPTED, leased)) {
			return;
		}
		try {
			int status = client.send(leased.fact());
			if (status >= 200 && status < 300) {
				if (outbox.compareAndSet(leased.id(), UsageOutboxState.ACCEPTED, leased.sync(clock.instant()))
						&& observability != null) {
					observability.transition(UsageOutboxState.SYNCED);
				}
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
	}

	private void retry(UsageOutboxItem item, String code) {
		Instant now = clock.instant();
		long base = properties.backoffInitial().toMillis();
		long ceiling = properties.backoffCeiling().toMillis();
		long delay = Math.min(ceiling, base * (1L << Math.min(20, Math.max(0, item.attemptCount() - 1))));
		// A deterministic per-item jitter avoids synchronized recovery without changing retry bounds.
		long jitter = Math.floorMod(item.id().hashCode(), Math.max(1L, Math.min(delay / 4L + 1L, 1_000L)));
		outbox.compareAndSet(item.id(), UsageOutboxState.ACCEPTED, item.retry(now.plusMillis(delay + jitter), code));
		if (observability != null) {
			observability.retry(item);
		}
		log.warn("Usage fact {} delivery deferred ({})", item.id(), code);
	}

	private void suspend(UsageOutboxItem item, String code) {
		outbox.compareAndSet(item.id(), UsageOutboxState.ACCEPTED,
				item.retry(Instant.ofEpochMilli(Long.MAX_VALUE), code));
		log.warn("Usage fact {} requires operator action ({})", item.id(), code);
	}
}
