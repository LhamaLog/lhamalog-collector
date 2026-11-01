// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.config;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.MongoProductTokenStore;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveClient;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveTracesSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.TracesSink;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Wires the OTLP receiver (T021). Both servers are {@code SmartLifecycle} beans, so Spring starts them after
 * the context is ready and stops them on shutdown. Each channel can be turned off with
 * {@code lhamalog.otlp.http.enabled} or {@code lhamalog.otlp.grpc.enabled}.
 */
@Configuration(proxyBeanMethods = false)
public class OtlpReceiverConfiguration {

	@Bean
	TokenValidator productTokenStore(MongoOperations mongo, OtlpReceiverProperties properties) {
		return new MongoProductTokenStore(mongo, Clock.systemUTC(), properties.tokenCache().toSettings(),
				Ticker.systemTicker());
	}

	@Bean
	OpenObserveClient openObserveClient(OtlpReceiverProperties properties) {
		OtlpReceiverProperties.OpenObserve oo = properties.openobserve();
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return new OpenObserveClient(http, oo.url(), oo.organization(), oo.username(), oo.password());
	}

	@Bean
	LogsSink logsSink(OpenObserveClient client, OtlpReceiverProperties properties) {
		return new OpenObserveLogsSink(client, properties.openobserve().logsStream(), Clock.systemUTC());
	}

	@Bean
	TracesSink tracesSink(OpenObserveClient client, OtlpReceiverProperties properties) {
		return new OpenObserveTracesSink(client, properties.openobserve().tracesStream(), Clock.systemUTC());
	}

	@Bean
	ExportLogsService exportLogsService(TokenValidator tokens, LogsSink sink, OtlpReceiverProperties properties) {
		return new ExportLogsService(tokens, sink, properties.timestamp().toWindow(), Clock.systemUTC());
	}

	@Bean
	ExportTracesService exportTracesService(TokenValidator tokens, TracesSink sink, OtlpReceiverProperties properties) {
		return new ExportTracesService(tokens, sink, properties.timestamp().toWindow(), Clock.systemUTC());
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.otlp.http.enabled", matchIfMissing = true)
	OtlpHttpServer otlpHttpServer(OtlpReceiverProperties properties, ExportLogsService logs, ExportTracesService traces) {
		return new OtlpHttpServer(properties.http().host(), properties.http().port(),
				properties.maxMessageBytes(), logs, traces, properties.httpLimits().toLimits());
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.otlp.grpc.enabled", matchIfMissing = true)
	OtlpGrpcServer otlpGrpcServer(OtlpReceiverProperties properties, ExportLogsService logs, ExportTracesService traces) {
		return new OtlpGrpcServer(properties.grpc().host(), properties.grpc().port(),
				properties.maxMessageBytes(), logs, traces);
	}

}
