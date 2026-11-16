package br.com.lhamalog.collector.ingestion.otlp.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import br.com.lhamalog.collector.ingestion.otlp.sink.TelemetryIngestedPublisher;
import br.com.lhamalog.collector.logging.CorrelationId;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T050 — gRPC request correlation id becomes stream {@code metadata.correlationId} (same resolution used
 * by {@link TelemetryIngestedPublisher#publishBatch}).
 */
class OtlpGrpcStreamCorrelationIdTest {

	private static final String GOOD = "good-token";
	private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization",
			Metadata.ASCII_STRING_MARSHALLER);

	private final AtomicReference<String> streamCorrelationId = new AtomicReference<>();
	private final AtomicReference<String> requestCorrelationId = new AtomicReference<>();
	private OtlpGrpcServer server;
	private ManagedChannel channel;

	@AfterEach
	void stop() throws InterruptedException {
		if (channel != null) {
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
		if (server != null) {
			server.stop();
		}
		MDC.clear();
	}

	@Test
	void givenCorrelationIdAppearsInStreamMetadata() {
		start();
		assertThat(logs("grpc-stream-cid-7").export(batch())).isNotNull();
		assertThat(streamCorrelationId.get()).isEqualTo("grpc-stream-cid-7");
	}

	@Test
	void generatedCorrelationIdMatchesStreamMetadata() {
		start();
		assertThat(logs(null).export(batch())).isNotNull();
		String generated = requestCorrelationId.get();
		assertThat(CorrelationId.isValid(generated)).isTrue();
		// Same value echoed by the interceptor and written to stream metadata.correlationId.
		assertThat(streamCorrelationId.get()).isEqualTo(generated);
	}

	private void start() {
		TokenValidator tokens = token -> GOOD.equals(token) ? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> {
			requestCorrelationId.set(CorrelationId.current());
			streamCorrelationId.set(TelemetryIngestedPublisher.requestCorrelationId());
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpGrpcServer("127.0.0.1", 0, 64 * 1024, logs,
				new ExportTracesService(tokens, (t, p, s) -> {
				}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls));
		server.start();
		channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
	}

	private LogsServiceGrpc.LogsServiceBlockingStub logs(String correlationId) {
		Metadata headers = new Metadata();
		headers.put(AUTHORIZATION, "Bearer " + GOOD);
		if (correlationId != null) {
			headers.put(CorrelationIdServerInterceptor.CORRELATION_ID_KEY, correlationId);
		}
		return LogsServiceGrpc.newBlockingStub(channel)
				.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
				.withDeadlineAfter(10, TimeUnit.SECONDS);
	}

	private static ExportLogsServiceRequest batch() {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder()
						.addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(LogRecord.getDefaultInstance()))))
				.build();
	}

}
