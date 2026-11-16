package br.com.lhamalog.collector.quota;

import br.com.lhamalog.collector.usage.domain.UsageDomain;

/** Port for the Core reservation API. Implementations must never use Authorization. */
public interface QuotaReservationClient {

	ReservationResult reserve(ReservationRequest request);

	void confirm(String reservationId);

	void release(String reservationId);

	int refund(String reservationId);

	record ReservationRequest(String reservationId, String tenantId, String productId, long requestedBytes,
			String batchId, UsageDomain domain) {

		public ReservationRequest {
			if (reservationId == null || reservationId.isBlank() || tenantId == null || tenantId.isBlank()
					|| productId == null || productId.isBlank() || requestedBytes <= 0 || batchId == null
					|| batchId.isBlank() || domain == null) {
				throw new IllegalArgumentException("invalid quota reservation request");
			}
		}
	}

	enum ReservationOutcome {
		AUTHORIZED, AUTHORIZED_UNLIMITED, INSUFFICIENT_BALANCE, NO_PLAN, SUSPENDED
	}

	/**
	 * Core reservation answer. {@code retentionDays} is optional (LLT-185): null or {@code <= 0} means unknown
	 * — the Collector must not change OpenObserve stream retention.
	 */
	record ReservationResult(ReservationOutcome outcome, Integer retentionDays) {

		public ReservationResult {
			if (outcome == null) {
				throw new IllegalArgumentException("outcome is required");
			}
			if (retentionDays != null && retentionDays <= 0) {
				retentionDays = null;
			}
		}

		public static ReservationResult of(ReservationOutcome outcome) {
			return new ReservationResult(outcome, null);
		}

		/** Known positive retention, or null when unknown. */
		public Integer knownRetentionDays() {
			return retentionDays;
		}
	}

	class UnavailableException extends RuntimeException {
		public UnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

		public UnavailableException(String message) {
			super(message);
		}
	}
}
