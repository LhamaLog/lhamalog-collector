// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.auth;

import java.util.Objects;

/**
 * Resultado de {@link TokenValidator}. So transporta o que a porta devolve;
 * nao decide se um token e valido.
 */
public sealed interface TokenValidationResult {

	/** Token aceito: identifica o produto e o tenant ao qual o produto pertence. */
	record Valid(String tenantId, String productId) implements TokenValidationResult {
		public Valid {
			Objects.requireNonNull(tenantId, "tenantId");
			Objects.requireNonNull(productId, "productId");
		}
	}

	/** Token ausente, vazio ou recusado. O motivo nao e exposto ao remetente. */
	record Unauthorized() implements TokenValidationResult {
	}

	/** Nao foi possivel consultar product_tokens ou products. */
	record CatalogUnavailable(Throwable cause) implements TokenValidationResult {
	}

}
