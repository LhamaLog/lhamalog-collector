package br.com.lhamalog.collector.usage.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable measurement of one accepted OTLP batch for a tenant, product and domain. acceptedAt is observational and is not part of idempotency. */
public record UsageFact(
		String usageFactId,
		String batchId,
		String tenantId,
		String productId,
		UsageDomain domain,
		List<String> acceptedRecordIds,
		int acceptedRecordCount,
		long acceptedBytes,
		Instant acceptedAt,
		int schemaVersion) {

	public UsageFact {
		requireText(usageFactId, "usageFactId");
		requireText(batchId, "batchId");
		requireText(tenantId, "tenantId");
		requireText(productId, "productId");
		Objects.requireNonNull(domain, "domain");
		acceptedRecordIds = List.copyOf(acceptedRecordIds);
		if (acceptedRecordIds.isEmpty() || new LinkedHashSet<>(acceptedRecordIds).size() != acceptedRecordIds.size()) {
			throw new IllegalArgumentException("acceptedRecordIds must be non-empty and unique");
		}
		if (acceptedRecordCount != acceptedRecordIds.size()) {
			throw new IllegalArgumentException("acceptedRecordCount must equal acceptedRecordIds size");
		}
		if (acceptedBytes < 0) {
			throw new IllegalArgumentException("acceptedBytes must not be negative");
		}
		Objects.requireNonNull(acceptedAt, "acceptedAt");
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("schemaVersion must be 1");
		}
	}

	public static UsageFact create(String tenantId, String productId, UsageDomain domain, List<String> acceptedRecordIds,
			long acceptedBytes, Instant acceptedAt) {
		String batchId = sha256(tenantId, productId, domain.name(), String.join("\u001f", acceptedRecordIds));
		return new UsageFact(sha256(tenantId, productId, domain.name(), batchId), batchId, tenantId, productId, domain,
				acceptedRecordIds, acceptedRecordIds.size(), acceptedBytes, acceptedAt, 1);
	}

	public boolean sameImmutableContent(UsageFact other) {
		return other != null && usageFactId.equals(other.usageFactId) && batchId.equals(other.batchId)
				&& tenantId.equals(other.tenantId) && productId.equals(other.productId) && domain == other.domain
				&& acceptedRecordIds.equals(other.acceptedRecordIds)
				&& acceptedRecordCount == other.acceptedRecordCount && acceptedBytes == other.acceptedBytes
				&& schemaVersion == other.schemaVersion;
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static String sha256(String... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String value : values) {
				byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
				digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
				digest.update((byte) ':');
				digest.update(bytes);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}
}
