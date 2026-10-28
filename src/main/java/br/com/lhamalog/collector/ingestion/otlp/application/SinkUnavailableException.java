package br.com.lhamalog.collector.ingestion.otlp.application;

import java.time.Duration;

/**
 * Lancada pelo sink quando nao pode aceitar o lote agora (back-pressure). Vira HTTP 503 com
 * {@code Retry-After} ou gRPC UNAVAILABLE com {@code RetryInfo}.
 */
public class SinkUnavailableException extends RuntimeException {

    /** Retry-After padrao da plataforma: 2 minutos. */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(2);

    private final Duration retryAfter;

    public SinkUnavailableException(String message) {
        this(message, DEFAULT_RETRY_AFTER);
    }

    public SinkUnavailableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
