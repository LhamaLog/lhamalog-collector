package br.com.lhamalog.collector.quota;

import br.com.lhamalog.collector.usage.domain.UsageDomain;

/** Port for the Core reservation API. Implementations must never use Authorization. */
public interface QuotaReservationClient {

	ReservationOutcome reserve(ReservationRequest request);

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
		AUTHORIZED, AUTHORIZED_UNLIMITED, INSUFFICIENT_BALANCE, NO_PLAN
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
