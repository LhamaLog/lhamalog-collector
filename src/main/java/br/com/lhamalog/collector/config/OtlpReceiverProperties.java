// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.config;

import br.com.lhamalog.collector.ingestion.otlp.auth.MongoProductTokenStore;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.http.HttpLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuracao do receptor OTLP e do destino OpenObserve (T009), prefixo {@code lhamalog.otlp}.
 *
 * <p>Usuario e senha do OpenObserve chegam so por variavel de ambiente
 * ({@code OPENOBSERVE_USERNAME}, {@code OPENOBSERVE_PASSWORD}). {@link #toString()} dos registros
 * aninhados nao expoe a senha.
 */
@ConfigurationProperties("lhamalog.otlp")
public record OtlpReceiverProperties(
		@DefaultValue Channel http,
		@DefaultValue Channel grpc,
		@DefaultValue("64MB") DataSize maxMessageSize,
		@DefaultValue OpenObserve openobserve,
		@DefaultValue Timestamp timestamp,
		@DefaultValue TokenCache tokenCache,
		@DefaultValue HttpLimitsProperties httpLimits) {

	/**
	 * Valida o tamanho maximo na partida (T045, FR-024): precisa ser maior que zero e caber em um {@code int},
	 * porque o gRPC usa {@code int}. Erro claro em vez de {@code ArithmeticException} no meio da criacao dos beans.
	 */
	public OtlpReceiverProperties {
		if (maxMessageSize == null || maxMessageSize.toBytes() <= 0) {
			throw new IllegalArgumentException("lhamalog.otlp.max-message-size must be greater than 0 (got "
					+ maxMessageSize + ")");
		}
		if (maxMessageSize.toBytes() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("lhamalog.otlp.max-message-size must be at most " + Integer.MAX_VALUE
					+ " bytes (about 2GB), got " + maxMessageSize.toBytes() + " bytes");
		}
	}

	/** {@link #maxMessageSize()} em bytes, ja validado para caber em {@code int}. */
	public int maxMessageBytes() {
		return (int) maxMessageSize.toBytes();
	}

	/** Um canal do receptor (HTTP ou gRPC). Porta 0 significa porta livre escolhida pelo sistema. */
	public record Channel(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("0.0.0.0") String host,
			@DefaultValue("0") int port) {
	}

	/** Destino OpenObserve para logs e traces (ADR_168). */
	public record OpenObserve(
			String url,
			@DefaultValue("lhamalog") String organization,
			@DefaultValue("logs") String logsStream,
			@DefaultValue("traces") String tracesStream,
			String username,
			String password) {

		@Override
		public String toString() {
			return "OpenObserve[url=" + url + ", organization=" + organization + ", logsStream=" + logsStream
					+ ", tracesStream=" + tracesStream + ", username=" + username + ", password=***]";
		}
	}

	/**
	 * Janela aceita para o horario dos registros (T040, FR-019). O padrao de idade maxima fica abaixo das 5h
	 * que o OpenObserve aceita por padrao ({@code ZO_INGEST_ALLOWED_UPTO}).
	 */
	public record Timestamp(
			@DefaultValue("290m") Duration maxAge,
			@DefaultValue("1h") Duration maxFuture) {

		public Timestamp {
			new TimestampWindow(maxAge, maxFuture);
		}

		public TimestampWindow toWindow() {
			return new TimestampWindow(maxAge, maxFuture);
		}
	}

	/** Caches do token (T042, FR-008): positivo para tokens validos, negativo pequeno para recusados. */
	public record TokenCache(
			@DefaultValue("30s") Duration positiveTtl,
			@DefaultValue("10000") long positiveMaxSize,
			@DefaultValue("3s") Duration negativeTtl,
			@DefaultValue("1000") long negativeMaxSize) {

		public TokenCache {
			new MongoProductTokenStore.CacheSettings(positiveTtl, positiveMaxSize, negativeTtl, negativeMaxSize);
		}

		public MongoProductTokenStore.CacheSettings toSettings() {
			return new MongoProductTokenStore.CacheSettings(positiveTtl, positiveMaxSize, negativeTtl, negativeMaxSize);
		}
	}

	/** Limites do servidor HTTP do JDK (T046, FR-025); veja {@link HttpLimits}. */
	public record HttpLimitsProperties(
			@DefaultValue("200") int maxConcurrentRequests,
			@DefaultValue("1000") int maxConnections,
			@DefaultValue("30s") Duration readTimeout,
			@DefaultValue("30s") Duration writeTimeout,
			@DefaultValue("30s") Duration idleTimeout,
			@DefaultValue("200") int maxIdleConnections) {

		public HttpLimitsProperties {
			new HttpLimits(maxConcurrentRequests, maxConnections, readTimeout, writeTimeout, idleTimeout,
					maxIdleConnections);
		}

		public HttpLimits toLimits() {
			return new HttpLimits(maxConcurrentRequests, maxConnections, readTimeout, writeTimeout, idleTimeout,
					maxIdleConnections);
		}
	}

}
