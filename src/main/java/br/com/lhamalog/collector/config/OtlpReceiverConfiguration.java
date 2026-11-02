// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.config;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.MongoProductTokenStore;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogIngestedPublisher;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveClient;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveTracesSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingTracesSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.TraceIngestedPublisher;
import br.com.lhamalog.collector.ingestion.otlp.sink.TracesSink;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the OTLP receiver (T021). Both servers are {@code SmartLifecycle} beans, so Spring starts them after
 * the context is ready and stops them on shutdown. Each channel can be turned off with
 * {@code lhamalog.otlp.http.enabled} or {@code lhamalog.otlp.grpc.enabled}.
 *
 * <p>Accepted logs go to OpenObserve and then to the Redis Stream {@code lhamalog:stream:logs}; accepted spans to
 * {@code lhamalog:stream:traces}. The stream carries only a reference to OpenObserve (LLT-182).
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
	LogIngestedPublisher logIngestedPublisher(StringRedisTemplate redis, Environment environment) {
		return new LogIngestedPublisher(redis, JsonMapper.builder().build(),
				new MessagingProperties(environment::getProperty), Clock.systemUTC());
	}

	@Bean
	TraceIngestedPublisher traceIngestedPublisher(StringRedisTemplate redis, Environment environment) {
		return new TraceIngestedPublisher(redis, JsonMapper.builder().build(),
				new MessagingProperties(environment::getProperty), Clock.systemUTC());
	}

	@Bean
	LogsSink logsSink(OpenObserveClient client, OtlpReceiverProperties properties, LogIngestedPublisher publisher) {
		return PublishingLogsSink.of(
				new OpenObserveLogsSink(client, properties.openobserve().logsStream(), Clock.systemUTC()), publisher);
	}

	@Bean
	TracesSink tracesSink(OpenObserveClient client, OtlpReceiverProperties properties,
			TraceIngestedPublisher publisher) {
		return PublishingTracesSink.of(
				new OpenObserveTracesSink(client, properties.openobserve().tracesStream(), Clock.systemUTC()), publisher);
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
