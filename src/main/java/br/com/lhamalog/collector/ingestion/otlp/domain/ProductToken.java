// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Document of the MongoDB collection {@code product_tokens} (T006). Replaces the old
 * {@code ProductCredential}: the token hash no longer lives inside {@code products}.
 *
 * <p>Relationship: {@code products} 1:N {@code product_tokens} through {@code productId}. Every product
 * belongs to one tenant ({@code products.tenantId}, required).
 *
 * <p>Lifecycle (executed outside this feature): a token is never physically deleted. Revoking only sets
 * {@code status} to {@link Status#REVOKED} and fills {@code revokedAt}. Physical deletion happens only in
 * cascade, when the related product is deleted. The collector does not register, rotate or revoke tokens;
 * it only reads them.
 *
 * <p>The token value itself is never a field. {@code tokenHash} is the lowercase hex SHA-256 of the UTF-8
 * bytes of the token and is unique in the collection.
 *
 * @param id document id, required
 * @param productId reference to {@code products}, required
 * @param tokenHash lowercase hex SHA-256 of the token, required
 * @param status {@code ACTIVE} or {@code REVOKED}, required
 * @param createdAt registration instant, required
 * @param revokedAt filled only on revocation, {@code null} while active
 * @param expiresAt optional; {@code null} means no expiry
 */
public record ProductToken(String id, String productId, String tokenHash, Status status, Instant createdAt,
		Instant revokedAt, Instant expiresAt) {

	public static final String COLLECTION = "product_tokens";

	public enum Status {

		ACTIVE, REVOKED

	}

	public ProductToken {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(tokenHash, "tokenHash");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(createdAt, "createdAt");
	}

	/**
	 * Invariants of the document: {@code REVOKED} requires {@code revokedAt}; {@code ACTIVE} has no
	 * {@code revokedAt}. A document that breaks them is kept readable so the validity rule can refuse it.
	 */
	public boolean satisfiesInvariants() {
		return switch (status) {
			case ACTIVE -> revokedAt == null;
			case REVOKED -> revokedAt != null;
		};
	}

}
