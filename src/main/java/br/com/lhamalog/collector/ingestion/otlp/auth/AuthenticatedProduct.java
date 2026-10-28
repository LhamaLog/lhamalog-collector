package br.com.lhamalog.collector.ingestion.otlp.auth;

/**
 * Produto identificado pelo token de API (Bearer) de uma requisicao OTLP.
 *
 * <p>{@code tokenId} e um identificador nao secreto do token (prefixo do SHA-256), seguro para logs.
 * A associacao ao tenant/produto real fica para a LLT-182.
 */
public record AuthenticatedProduct(String tokenId) {
}
