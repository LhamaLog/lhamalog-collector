package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.config.UsageProperties;
import br.com.lhamalog.collector.usage.core.UsageOutboxObservability;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Reconciles pending Mongo candidates from the Redis evidence, never from
 * stream inspection or elapsed time. Grace only avoids racing a live request.
 */
public final class UsageAcceptanceReconciler {

	private static final Logger log = LoggerFactory.getLogger(UsageAcceptanceReconciler.class);

	private final UsageOutboxRepository outbox;
	private final UsageAcceptanceEvidence evidence;
	private final Clock clock;

	private final UsageProperties properties;

	private final UsageOutboxObservability observability;

	public UsageAcceptanceReconciler(UsageOutboxRepository outbox, UsageAcceptanceEvidence evidence, Clock clock) {
		this(outbox, evidence, clock, null, null);
	}

	public UsageAcceptanceReconciler(UsageOutboxRepository outbox, UsageAcceptanceEvidence evidence, Clock clock,
			UsageProperties properties) {
		this(outbox, evidence, clock, properties, null);
	}

	public UsageAcceptanceReconciler(UsageOutboxRepository outbox, UsageAcceptanceEvidence evidence, Clock clock,
			UsageProperties properties, UsageOutboxObservability observability) {
		this.outbox = outbox;
		this.evidence = evidence;
		this.clock = clock;
		this.properties = properties;
		this.observability = observability == null ? UsageOutboxObservability.NOOP : observability;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void reconcileAtStartup() {
		reconcileNow();
	}

	@Scheduled(fixedDelayString = "${lhamalog.usage.worker-interval:10s}")
	public void reconcileNow() {
		if (properties != null) {
			reconcilePendingBefore(clock.instant().minus(properties.grace()));
		}
	}

	public void reconcilePendingBefore(Instant graceDeadline) {
		for (UsageOutboxItem item : outbox.findPendingBefore(graceDeadline)) {
			if (properties != null) {
				observability.pendingAge(item, clock.instant(), properties.grace());
			}
			String value = evidence.resolvePending(item.acceptedEvidenceKey());
			if (value.startsWith("ABANDONED")) {
				if (outbox.compareAndSet(item, item.abandon())) {
					observability.transition(UsageOutboxState.ABANDONED);
				}
			}
			else if (value.startsWith("DONE")) {
				if (outbox.compareAndSet(item, item.accept())) {
					observability.transition(UsageOutboxState.ACCEPTED);
				}
			}
			else if (value.startsWith("CLAIMED")) {
				if (outbox.compareAndSet(item, item.markAmbiguous())) {
					observability.ambiguous();
					log.error("Usage fact {} is ambiguous after Redis claim", item.id());
				}
			}
			else {
				log.warn("Usage fact {} has unknown Redis evidence state", item.id());
			}
		}
	}
}
