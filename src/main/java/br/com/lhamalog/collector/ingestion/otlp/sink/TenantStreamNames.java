package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic OpenObserve stream names per tenant (LLT-185). Safe charset {@code [a-z0-9_]}; collision-safe
 * when sanitization changes or truncates the id.
 */
public final class TenantStreamNames {

	/** Max length of the sanitized tenant segment before an optional hash suffix. */
	public static final int MAX_SANITIZED_LENGTH = 40;

	private TenantStreamNames() {
	}

	/**
	 * Builds {@code {prefix}_{sanitized}} plus {@code _} and the first 8 hex chars of SHA-256(tenantId) when
	 * sanitization altered or truncated the lowercased id.
	 */
	public static String resolve(String prefix, String tenantId) {
		Objects.requireNonNull(prefix, "prefix");
		Objects.requireNonNull(tenantId, "tenantId");
		if (prefix.isBlank()) {
			throw new IllegalArgumentException("stream prefix must not be blank");
		}
		if (tenantId.isBlank()) {
			throw new IllegalArgumentException("tenantId must not be blank");
		}
		String lower = tenantId.toLowerCase(Locale.ROOT);
		StringBuilder sanitized = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
				sanitized.append(c);
			}
			else {
				sanitized.append('_');
			}
		}
		String full = sanitized.toString();
		boolean truncated = full.length() > MAX_SANITIZED_LENGTH;
		String segment = truncated ? full.substring(0, MAX_SANITIZED_LENGTH) : full;
		// Hash when lowercasing/sanitizing changed the id or when truncated (collision safety).
		boolean changed = !full.equals(lower) || truncated;
		if (changed) {
			return prefix + "_" + segment + "_" + hash8(tenantId);
		}
		return prefix + "_" + segment;
	}

	private static String hash8(String tenantId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(tenantId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
