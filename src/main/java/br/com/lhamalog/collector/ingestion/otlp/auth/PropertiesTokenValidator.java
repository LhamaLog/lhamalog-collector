package br.com.lhamalog.collector.ingestion.otlp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PROVISORIO (LLT-181): valida tokens a partir de listas em propriedades
 * ({@code lhamalog.otlp.auth.valid-tokens} e {@code lhamalog.otlp.auth.revoked-tokens}).
 *
 * <p>Os tokens sao mantidos apenas como hash SHA-256 em memoria. Sera substituido pela validacao contra o
 * MongoDB (AES-256-GCM + indice SHA-256). Revogado tem precedencia sobre valido.
 */
public class PropertiesTokenValidator implements TokenValidator {

    private final Set<String> validHashes;
    private final Set<String> revokedHashes;

    public PropertiesTokenValidator(Collection<String> validTokens, Collection<String> revokedTokens) {
        this.validHashes = hashAll(validTokens);
        this.revokedHashes = hashAll(revokedTokens);
    }

    @Override
    public TokenValidationResult validate(String token) {
        if (token == null || token.isBlank()) {
            return new TokenValidationResult.Invalid("token ausente");
        }
        String hash = sha256(token);
        if (revokedHashes.contains(hash)) {
            return new TokenValidationResult.Revoked();
        }
        if (validHashes.contains(hash)) {
            return new TokenValidationResult.Valid(new AuthenticatedProduct(hash.substring(0, 12)));
        }
        return new TokenValidationResult.Invalid("token desconhecido");
    }

    private static Set<String> hashAll(Collection<String> tokens) {
        if (tokens == null) {
            return Set.of();
        }
        return tokens.stream().filter(t -> t != null && !t.isBlank()).map(String::trim)
                .map(PropertiesTokenValidator::sha256).collect(Collectors.toUnmodifiableSet());
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
