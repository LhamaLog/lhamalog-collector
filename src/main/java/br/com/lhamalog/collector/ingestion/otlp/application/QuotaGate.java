package br.com.lhamalog.collector.ingestion.otlp.application;

import java.util.List;

/** Quota policy port kept outside sink implementations so delivery always follows a confirmed reservation. */
public interface QuotaGate<R, T> {
	Reservation reserve(String tenantId, String productId, R request, List<T> accepted);

	void confirm(String reservationId);

	void release(String reservationId);

	/** Records a durable compensation after a confirmed reservation cannot be delivered. */
	default void refund(String reservationId, String reason) {
	}

	default void coreUnavailable() {
	}

	default void postDebitWriteFailure() {
	}

	record Reservation(Outcome outcome, String reservationId) {
		public enum Outcome { AUTHORIZED, INSUFFICIENT_BALANCE, NO_PLAN, UNAVAILABLE }
	}
}
