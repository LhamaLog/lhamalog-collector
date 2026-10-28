package br.com.lhamalog.collector.ingestion.otlp.auth;

/**
 * Valida o token de API enviado em {@code Authorization: Bearer <token>}.
 *
 * <p>Tokens sao revogaveis e nao expiram. A implementacao definitiva (token armazenado com AES-256-GCM e
 * indice SHA-256 no MongoDB) substituira {@link PropertiesTokenValidator}.
 */
public interface TokenValidator {

    TokenValidationResult validate(String token);

    /** Extrai o token de um valor de header {@code Authorization}; retorna null se nao for Bearer. */
    static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
