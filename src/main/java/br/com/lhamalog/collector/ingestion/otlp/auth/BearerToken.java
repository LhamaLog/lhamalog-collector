// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.auth;

import java.util.Locale;

/** Extracts the token from an {@code Authorization} value. Never logs it. */
public final class BearerToken {

	private static final String PREFIX = "bearer ";

	private BearerToken() {
	}

	/** @return the token, or {@code null} when the value is missing or is not a Bearer credential */
	public static String extract(String authorization) {
		if (authorization == null) {
			return null;
		}
		String value = authorization.strip();
		if (value.length() <= PREFIX.length()
				|| !value.substring(0, PREFIX.length()).toLowerCase(Locale.ROOT).equals(PREFIX)) {
			return null;
		}
		String token = value.substring(PREFIX.length()).strip();
		return token.isEmpty() ? null : token;
	}

}
