package br.com.lhamalog.collector.ingestion.otlp.application;

/**
 * Request-scoped OTLP transport ({@code http} or {@code grpc}) for metric tags. Set by the receivers
 * around each export call; cleared in {@code finally}.
 */
public final class IngestionProtocol {

	public static final String HTTP = "http";

	public static final String GRPC = "grpc";

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private IngestionProtocol() {
	}

	public static void set(String protocol) {
		CURRENT.set(protocol);
	}

	public static String current() {
		String value = CURRENT.get();
		return value == null || value.isBlank() ? "unknown" : value;
	}

	public static void clear() {
		CURRENT.remove();
	}
}
