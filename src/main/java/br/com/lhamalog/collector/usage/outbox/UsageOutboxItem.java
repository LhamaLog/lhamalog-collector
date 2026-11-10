package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.time.Instant;
import java.util.Objects;

public record UsageOutboxItem(
		String id,
		UsageFact fact,
		UsageOutboxState state,
		int attemptCount,
		Instant nextAttemptAt,
		String leaseOwner,
		Instant leaseUntil,
		String lastErrorCode,
		Instant createdAt,
		Instant deliveredAt,
		String acceptedEvidenceKey) {

	public UsageOutboxItem {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(fact, "fact");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(acceptedEvidenceKey, "acceptedEvidenceKey");
		if (!id.equals(fact.usageFactId()) || attemptCount < 0) {
			throw new IllegalArgumentException("invalid outbox item");
		}
	}

	public static UsageOutboxItem pending(UsageFact fact, Instant now) {
		return new UsageOutboxItem(fact.usageFactId(), fact, UsageOutboxState.PENDING, 0, now, null, null, null, now,
				null, "usage:fact:" + fact.usageFactId());
	}

	public UsageOutboxItem accept() {
		return transition(UsageOutboxState.PENDING, UsageOutboxState.ACCEPTED, nextAttemptAt, null, null, null);
	}

	public UsageOutboxItem abandon() {
		return transition(UsageOutboxState.PENDING, UsageOutboxState.ABANDONED, nextAttemptAt, null, null, null);
	}

	public UsageOutboxItem markAmbiguous() {
		return transition(UsageOutboxState.PENDING, UsageOutboxState.AMBIGUOUS, nextAttemptAt, null, null, null);
	}

	public UsageOutboxItem lease(String owner, Instant now, Instant until) {
		if (owner == null || owner.isBlank() || until == null || !until.isAfter(now)
				|| state != UsageOutboxState.ACCEPTED || leaseUntil != null && !leaseUntil.isBefore(now)) {
			throw new IllegalStateException("item is not available for lease");
		}
		return new UsageOutboxItem(id, fact, state, attemptCount + 1, nextAttemptAt, owner, until, lastErrorCode,
				createdAt, deliveredAt, acceptedEvidenceKey);
	}

	public UsageOutboxItem retry(Instant nextAt, String errorCode) {
		if (state != UsageOutboxState.ACCEPTED) {
			throw new IllegalStateException("only accepted items can retry");
		}
		return new UsageOutboxItem(id, fact, state, attemptCount, nextAt, null, null, errorCode, createdAt, deliveredAt,
				acceptedEvidenceKey);
	}

	public UsageOutboxItem sync(Instant now) {
		return transition(UsageOutboxState.ACCEPTED, UsageOutboxState.SYNCED, nextAttemptAt, null, null, now);
	}

	private UsageOutboxItem transition(UsageOutboxState expected, UsageOutboxState target, Instant nextAt,
			String owner, Instant until, Instant delivered) {
		if (state != expected) {
			throw new IllegalStateException("invalid outbox transition from " + state + " to " + target);
		}
		return new UsageOutboxItem(id, fact, target, attemptCount, nextAt, owner, until, lastErrorCode, createdAt,
				delivered, acceptedEvidenceKey);
	}
}
