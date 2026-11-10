package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.core.UsageOutboxObservability;
import java.time.Clock;
import java.time.Instant;

/**
 * Boundary used by ingestion after OpenObserve confirms a batch. Persisting the
 * immutable PENDING candidate is deliberately separate from Redis acceptance.
 */
public final class UsageFactRecorder {

	private final UsageOutboxRepository outbox;
	private final Clock clock;

	private final UsageOutboxObservability observability;

	public UsageFactRecorder(UsageOutboxRepository outbox, Clock clock) {
		this(outbox, clock, null);
	}

	public UsageFactRecorder(UsageOutboxRepository outbox, Clock clock, UsageOutboxObservability observability) {
		this.outbox = outbox;
		this.clock = clock;
		this.observability = observability;
	}

	public UsageOutboxItem recordPending(UsageFact fact) {
		UsageOutboxItem item = outbox.insertPending(fact, clock.instant());
		if (observability != null) {
			observability.transition(item.state());
		}
		return item;
	}

	public boolean accept(UsageOutboxItem item) {
		boolean accepted = outbox.compareAndSet(item.id(), UsageOutboxState.PENDING, item.accept());
		if (accepted && observability != null) {
			observability.transition(UsageOutboxState.ACCEPTED);
		}
		return accepted;
	}

	public boolean abandon(UsageOutboxItem item) {
		boolean abandoned = outbox.compareAndSet(item.id(), UsageOutboxState.PENDING, item.abandon());
		if (abandoned && observability != null) {
			observability.transition(UsageOutboxState.ABANDONED);
		}
		return abandoned;
	}

	public boolean markAmbiguous(UsageOutboxItem item) {
		boolean ambiguous = outbox.compareAndSet(item.id(), UsageOutboxState.PENDING, item.markAmbiguous());
		if (ambiguous && observability != null) {
			observability.ambiguous();
		}
		return ambiguous;
	}
}
