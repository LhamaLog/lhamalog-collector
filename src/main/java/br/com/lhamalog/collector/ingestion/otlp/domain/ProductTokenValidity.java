// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Validity rule of a product token (T008a). Pure: no I/O, the caller passes what it read.
 *
 * <p>A token is valid only when, at the same time: exactly one document has its hash; the document keeps
 * its invariants; {@code status} is {@code ACTIVE}; {@code revokedAt} is absent; {@code expiresAt} is absent
 * or after the request instant; and the related product exists with a non blank {@code tenantId}. More than
 * one document with the same hash is an invalid configuration and the token is refused. Any refusal is the
 * same "unauthorized" for the sender: the reason is never exposed.
 */
public final class ProductTokenValidity {

	private ProductTokenValidity() {
	}

	/**
	 * @param matches documents found for the token hash (the caller may stop reading after two)
	 * @param now instant of the request
	 * @return the token when it may be used, pending the product check of {@link #tenantOf}
	 */
	public static Optional<ProductToken> usableToken(List<ProductToken> matches, Instant now) {
		if (matches == null || matches.size() != 1) {
			return Optional.empty();
		}
		ProductToken token = matches.getFirst();
		boolean usable = token.satisfiesInvariants() && token.status() == ProductToken.Status.ACTIVE
				&& token.revokedAt() == null && (token.expiresAt() == null || token.expiresAt().isAfter(now));
		return usable ? Optional.of(token) : Optional.empty();
	}

	/**
	 * @param productExists whether the product referenced by the token exists
	 * @param tenantId {@code tenantId} read from that product, may be {@code null}
	 * @return the tenant when the product makes the token valid
	 */
	public static Optional<String> tenantOf(boolean productExists, String tenantId) {
		if (!productExists || tenantId == null || tenantId.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(tenantId);
	}

}
