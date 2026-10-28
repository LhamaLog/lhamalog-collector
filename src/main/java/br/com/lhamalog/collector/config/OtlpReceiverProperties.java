package br.com.lhamalog.collector.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Configuracao dos receptores OTLP (prefixo {@code lhamalog.otlp}). */
@ConfigurationProperties("lhamalog.otlp")
public record OtlpReceiverProperties(
        @DefaultValue Http http,
        @DefaultValue Grpc grpc,
        @DefaultValue Auth auth) {

    /** OTLP/HTTP. Porta 0 = porta aleatoria (testes). */
    public record Http(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.0.0.0") String host,
            @DefaultValue("4318") int port,
            @DefaultValue("4MB") DataSize maxRequestSize) {
    }

    /** OTLP/gRPC. Porta 0 = porta aleatoria (testes). */
    public record Grpc(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.0.0.0") String host,
            @DefaultValue("4317") int port,
            @DefaultValue("4MB") DataSize maxMessageSize) {
    }

    /**
     * PROVISORIO (LLT-181): tokens de API aceitos e revogados, ate a validacao contra o MongoDB.
     * Nunca coloque tokens reais em arquivo versionado; em prod use variavel de ambiente.
     */
    public record Auth(
            @DefaultValue List<String> validTokens,
            @DefaultValue List<String> revokedTokens) {
    }
}
