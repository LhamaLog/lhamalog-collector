// Author: LhamaLog Desenvolvimento (IA) - LLT-184
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
import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import br.com.lhamalog.collector.usage.core.CoreUsageFactsClient;
import br.com.lhamalog.collector.usage.core.UsageOutboxWorker;
import br.com.lhamalog.collector.usage.core.UsageOutboxObservability;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.MongoUsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import br.com.lhamalog.collector.usage.outbox.UsageAcceptanceReconciler;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxRepository;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the OTLP receiver (T021). Both servers are {@code SmartLifecycle} beans, so Spring starts them after
 * the context is ready and stops them on shutdown. Each channel can be turned off with
 * {@code lhamalog.otlp.http.enabled} or {@code lhamalog.otlp.grpc.enabled}.
 *
 * <p>Accepted logs go to OpenObserve and then to the Redis Stream {@code lhamalog:stream:logs}; accepted spans to
 * {@code lhamalog:stream:traces}. The stream carries only a reference to OpenObserve (LLT-182). Publish retries and
 * DLQ are configured via {@link MessagingProperties} (LLT-184).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OtlpReceiverConfiguration {

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageOutboxRepository usageOutboxRepository(org.springframework.data.mongodb.core.MongoTemplate mongo) {
		return new MongoUsageOutboxRepository(mongo);
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageFactFactory usageFactFactory() {
		return new UsageFactFactory(Clock.systemUTC());
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageOutboxObservability usageOutboxObservability(io.micrometer.core.instrument.MeterRegistry meters,
			UsageProperties properties) {
		UsageOutboxObservability observability = new UsageOutboxObservability(meters);
		observability.evidenceExpiryConfigured(properties.deduplicationTtl());
		return observability;
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageFactRecorder usageFactRecorder(UsageOutboxRepository outbox, UsageOutboxObservability observability) {
		return new UsageFactRecorder(outbox, Clock.systemUTC(), observability);
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageAcceptanceReconciler usageAcceptanceReconciler(UsageOutboxRepository outbox, StringRedisTemplate redis,
			UsageProperties properties, UsageOutboxObservability observability) {
		return new UsageAcceptanceReconciler(outbox, new br.com.lhamalog.collector.usage.outbox.UsageAcceptanceEvidence() {
			@Override
			public String read(String key) {
				return redis.opsForValue().get(key);
			}

			@Override
			public String resolvePending(String key) {
				org.springframework.data.redis.core.script.DefaultRedisScript<String> script =
						new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
								local current = redis.call('GET', KEYS[1])
								if current then return current end
								redis.call('SET', KEYS[1], 'ABANDONED', 'EX', ARGV[1])
								return 'ABANDONED'
								""", String.class);
				return redis.execute(script, java.util.List.of(key),
						Long.toString(properties.deduplicationTtl().toSeconds()));
			}
		}, Clock.systemUTC(), properties, observability);
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	CoreAuthenticationProvider coreAuthenticationProvider() {
		return CoreAuthenticationProvider.fromEnvironment();
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	CoreUsageFactsClient coreUsageFactsClient(UsageProperties properties, CoreAuthenticationProvider authentication) {
		return new CoreUsageFactsClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				URI.create(properties.core().url()), authentication);
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "lhamalog.usage.enabled")
	UsageOutboxWorker usageOutboxWorker(UsageOutboxRepository outbox, CoreUsageFactsClient core,
			UsageProperties properties, UsageOutboxObservability observability) {
		return new UsageOutboxWorker(outbox, core, properties, Clock.systemUTC(), observability);
	}

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
	LogIngestedPublisher logIngestedPublisher(StringRedisTemplate redis, JsonMapper json,
			MessagingProperties messaging) {
		return new LogIngestedPublisher(redis, json, messaging, Clock.systemUTC());
	}

	@Bean
	TraceIngestedPublisher traceIngestedPublisher(StringRedisTemplate redis, JsonMapper json,
			MessagingProperties messaging) {
		return new TraceIngestedPublisher(redis, json, messaging, Clock.systemUTC());
	}

	@Bean
	LogsSink logsSink(OpenObserveClient client, OtlpReceiverProperties properties, LogIngestedPublisher publisher,
			ObjectProvider<UsageFactFactory> usageFacts, ObjectProvider<UsageFactRecorder> usageRecorder,
			ObjectProvider<UsageProperties> usageProperties) {
		UsageProperties usage = usageProperties.getIfAvailable();
		return new PublishingLogsSink(new OpenObserveLogsSink(client, properties.openobserve().logsStream(), Clock.systemUTC()),
				publisher::publishBatch, usageFacts.getIfAvailable(), usageRecorder.getIfAvailable(),
				usage == null ? null : (tenant, product, batch, records, key) -> publisher.publishBatchWithUsageEvidence(
						tenant, product, batch, records, key, usage.deduplicationTtl(), "logs"));
	}

	@Bean
	TracesSink tracesSink(OpenObserveClient client, OtlpReceiverProperties properties,
			TraceIngestedPublisher publisher, ObjectProvider<UsageFactFactory> usageFacts,
			ObjectProvider<UsageFactRecorder> usageRecorder, ObjectProvider<UsageProperties> usageProperties) {
		UsageProperties usage = usageProperties.getIfAvailable();
		return new PublishingTracesSink(
				new OpenObserveTracesSink(client, properties.openobserve().tracesStream(), Clock.systemUTC()),
				publisher::publishBatch, usageFacts.getIfAvailable(), usageRecorder.getIfAvailable(),
				usage == null ? null : (tenant, product, batch, records, key) -> publisher.publishBatchWithUsageEvidence(
						tenant, product, batch, records, key, usage.deduplicationTtl(), "traces"));
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