package br.com.lhamalog.collector.ingestion.otlp.application;

import java.util.List;

/**
 * Port for the final, potentially expensive, batch sanitization step. The default
 * is deliberately identity so existing OTLP processing remains unchanged.
 */
@FunctionalInterface
public interface BatchSanitizer<R, T> {

	List<T> sanitize(R request, List<T> accepted);

	static <R, T> BatchSanitizer<R, T> identity() {
		return (request, accepted) -> accepted;
	}

	final class RejectedException extends RuntimeException {
		public RejectedException(String message) {
			super(message);
		}
	}
}
