package br.com.lhamalog.collector.ingestion.otlp.auth;

/** Resultado da validacao de um token de API. */
public sealed interface TokenValidationResult {

    record Valid(AuthenticatedProduct product) implements TokenValidationResult {
    }

    /** Token ausente, malformado ou desconhecido. */
    record Invalid(String reason) implements TokenValidationResult {
    }

    /** Token que existiu, mas foi revogado. */
    record Revoked() implements TokenValidationResult {
    }
}
