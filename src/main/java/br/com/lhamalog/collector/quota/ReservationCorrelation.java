package br.com.lhamalog.collector.quota;

/**
 * Request-scoped reservation id for optional stream metadata. Set only after a successful confirm and
 * cleared before the OTLP handler returns, so publishers never observe a stale value.
 */
public final class ReservationCorrelation {
	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private ReservationCorrelation() {
	}

	public static void set(String reservationId) {
		if (reservationId == null || reservationId.isBlank()) {
			CURRENT.remove();
		}
		else {
			CURRENT.set(reservationId);
		}
	}

	public static String current() {
		return CURRENT.get();
	}

	public static void clear() {
		CURRENT.remove();
	}
}
