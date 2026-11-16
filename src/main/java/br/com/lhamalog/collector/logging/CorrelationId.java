// Author: LhamaLog Desenvolvimento (IA) - LLT-183 logging standard
package br.com.lhamalog.collector.logging;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation id for request tracing across HTTP/gRPC receivers and outbound Core calls.
 *
 * <p>MDC key {@value #KEY}; inbound HTTP header {@value #HEADER}; gRPC metadata
 * {@value #GRPC_METADATA_KEY}. Accepts only {@code ^[A-Za-z0-9._-]{1,64}$}; otherwise a new UUID.
 */
public final class CorrelationId {

	public static final String KEY = "correlationId";

	public static final String HEADER = "X-Correlation-Id";

	public static final String GRPC_METADATA_KEY = "x-correlation-id";

	private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

	private CorrelationId() {
	}

	/** Whether {@code value} is a non-blank id matching the acceptance regex. */
	public static boolean isValid(String value) {
		return value != null && VALID.matcher(value).matches();
	}

	/** Random UUID suitable as a correlation id (36 chars, allowed charset). */
	public static String newId() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Returns {@code inbound} when valid; otherwise a newly generated id.
	 */
	public static String resolve(String inbound) {
		return isValid(inbound) ? inbound : newId();
	}

	/** Puts the id into the current thread's MDC. */
	public static void put(String id) {
		MDC.put(KEY, id);
	}

	/** Removes the correlation id from the current thread's MDC. */
	public static void clear() {
		MDC.remove(KEY);
	}

	/** Current MDC value, or {@code null} when absent. */
	public static String current() {
		return MDC.get(KEY);
	}

	/**
	 * Wraps {@code task} so it runs with the caller's correlation id in the MDC of the executing thread
	 * (e.g. an executor), restoring that thread's previous value afterwards.
	 */
	public static <T> Supplier<T> propagate(Supplier<T> task) {
		String captured = current();
		return () -> {
			String previous = current();
			if (captured != null) {
				put(captured);
			}
			try {
				return task.get();
			}
			finally {
				if (previous != null) {
					put(previous);
				}
				else {
					clear();
				}
			}
		};
	}

}
