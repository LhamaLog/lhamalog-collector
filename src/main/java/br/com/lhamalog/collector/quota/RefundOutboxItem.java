package br.com.lhamalog.collector.quota;

import java.time.Instant;

public record RefundOutboxItem(
		String reservationId,
		RefundOutboxState state,
		String reason,
		int attemptCount,
		Instant nextAttemptAt,
		Instant createdAt,
		Instant updatedAt,
		String lastError) {

	static RefundOutboxItem pending(String reservationId, String reason, Instant now) {
		return new RefundOutboxItem(reservationId, RefundOutboxState.PENDING, reason, 0, now, now, now, null);
	}

	RefundOutboxItem retry(Instant now, Instant next, String error) {
		return new RefundOutboxItem(reservationId, RefundOutboxState.PENDING, reason, attemptCount + 1, next,
				createdAt, now, error);
	}

	RefundOutboxItem done(Instant now) {
		return new RefundOutboxItem(reservationId, RefundOutboxState.DONE, reason, attemptCount, nextAttemptAt,
				createdAt, now, null);
	}

	RefundOutboxItem suspended(Instant now, String error) {
		return new RefundOutboxItem(reservationId, RefundOutboxState.SUSPENDED, reason, attemptCount, nextAttemptAt,
				createdAt, now, error);
	}
}
