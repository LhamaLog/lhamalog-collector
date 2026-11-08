// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

/** Stream naming rule {@code lhamalog:stream:<domain>} from the ADR draft. */
public final class StreamNames {

	public static final String PREFIX = "lhamalog:stream:";

	private StreamNames() {
	}

	public static String stream(String domain) {
		if (domain == null || domain.isBlank()) {
			throw new IllegalArgumentException("domain must not be blank");
		}
		return PREFIX + domain;
	}

	/** Dead-letter stream for a domain, e.g. {@code logs} → {@code lhamalog:stream:logs:dlq}. */
	public static String dlq(String domain) {
		return stream(domain + ":dlq");
	}

}