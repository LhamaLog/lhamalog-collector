package br.com.lhamalog.collector.usage.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreAuthenticationProvider {

	public static final String ENVIRONMENT_VARIABLE = "LHAMALOG_COLLECTOR_CORE_TOKEN";

	private static final Logger log = LoggerFactory.getLogger(CoreAuthenticationProvider.class);

	private final String token;

	public CoreAuthenticationProvider(String token) {
		this.token = Optional.ofNullable(normalize(token)).filter(value -> !value.isBlank())
			.orElseThrow(() -> new IllegalArgumentException(ENVIRONMENT_VARIABLE + " must be configured"));
		log.info("Collector Core service token configured length={} sha256={}", this.token.length(),
				sha256Hex(this.token));
	}

	public static CoreAuthenticationProvider fromEnvironment() {
		return new CoreAuthenticationProvider(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public String serviceToken() {
		return token;
	}

	static String normalize(String token) {
		if (token == null) {
			return null;
		}
		String value = token.strip();
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if (first == last && (first == '"' || first == '\'')) {
				value = value.substring(1, value.length() - 1).strip();
			}
		}
		return value;
	}

	static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
