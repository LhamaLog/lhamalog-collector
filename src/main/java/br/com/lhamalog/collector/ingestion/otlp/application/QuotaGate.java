package br.com.lhamalog.collector.ingestion.otlp.application;

import java.util.List;

/** Quota policy port kept outside sink implementations so delivery always follows a confirmed reservation. */
public interface QuotaGate<R, T> {
	Reservation reserve(String tenantId, String productId, R request, List<T> accepted);

	/**
	 * Deterministic batch key identical to the reservation id for the same tenant/product/canonical bytes.
	 * Used for Core-unavailable attempt budget when reserve times out before returning a {@link Reservation}.
	 */
	default String batchKey(String tenantId, String productId, R request, List<T> accepted) {
		return null;
	}

	void confirm(String reservationId);

	void release(String reservationId);

	/** Records a durable compensation after a confirmed reservation cannot be delivered. */
	default void refund(String reservationId, String reason) {
	}

	default void coreUnavailable() {
	}

	default void postDebitWriteFailure() {
	}

	/**
	 * @param retentionDays optional plan retention from Core (LLT-185); null when unknown
	 */
	record Reservation(Outcome outcome, String reservationId, Integer retentionDays) {

		public Reservation(Outcome outcome, String reservationId) {
			this(outcome, reservationId, null);
		}

		public enum Outcome {
			AUTHORIZED,
			INSUFFICIENT_BALANCE,
			NO_PLAN,
			UNAVAILABLE,
			/** Subscription suspended; Collector maps to OTLP 403 when Core client is wired. */
			SUSPENDED
		}
	}
}
