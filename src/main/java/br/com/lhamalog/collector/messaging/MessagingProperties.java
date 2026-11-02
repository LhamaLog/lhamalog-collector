// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import java.util.Locale;
import java.util.function.Function;

/**
 * Stream length limits. The default is {@value #DEFAULT_MAX_LEN} entries; a stream can override it with the
 * environment variable {@code LHAMALOG_MESSAGING_STREAM_<DOMAIN>_MAX_LEN}, where {@code <DOMAIN>} is the
 * domain in upper case with every non alphanumeric character replaced by {@code _}.
 */
public final class MessagingProperties {

	public static final long DEFAULT_MAX_LEN = 100_000L;

	private final Function<String, String> lookup;

	/** @param lookup reads a variable by name, e.g. {@code System::getenv} or {@code Environment::getProperty} */
	public MessagingProperties(Function<String, String> lookup) {
		this.lookup = lookup;
	}

	public static String maxLenKey(String domain) {
		return "LHAMALOG_MESSAGING_STREAM_" + domain.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_MAX_LEN";
	}

	/** Fails fast on a value that is not a positive integer, so a typo does not disable trimming. */
	public long maxLenFor(String domain) {
		String key = maxLenKey(domain);
		String raw = lookup.apply(key);
		if (raw == null || raw.isBlank()) {
			return DEFAULT_MAX_LEN;
		}
		long value;
		try {
			value = Long.parseLong(raw.strip());
		}
		catch (NumberFormatException ex) {
			throw new IllegalStateException(key + " must be a positive integer", ex);
		}
		if (value <= 0) {
			throw new IllegalStateException(key + " must be a positive integer");
		}
		return value;
	}

}
