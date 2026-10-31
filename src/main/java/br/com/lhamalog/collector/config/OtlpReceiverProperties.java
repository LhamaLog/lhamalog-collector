// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.config;

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
		@DefaultValue OpenObserve openobserve) {

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

}
