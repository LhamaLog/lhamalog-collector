package br.com.lhamalog.collector.ingestion.otlp.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T011 / T019 / T041 — gRPC rate (RetryInfo 5 vs 300) and batch size refusals. */
class OtlpGrpcRateAndSizeTest {

	private static final String GOOD = "good-token";
	private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization",
			Metadata.ASCII_STRING_MARSHALLER);

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
	}

	@Test
	void rateLimitedIsResourceExhaustedWithRetryInfo5() {
		AtomicInteger delivered = new AtomicInteger();
		start(tenant -> TenantRateLimiter.Verdict.LIMITED, 64 * 1024, delivered);
		StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class,
				() -> logs().export(batch(LogRecord.getDefaultInstance())));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
		assertThat(GrpcRetryInfo.retryDelaySeconds(ex)).isEqualTo(5L);
		GrpcRetryInfo.assertNoLegacyRetryAfterTrailer(ex);
		assertThat(delivered).hasValue(0);
	}

	@Test
	void rateBlockedIsResourceExhaustedWithRetryInfo300() {
		start(tenant -> TenantRateLimiter.Verdict.BLOCKED, 64 * 1024, new AtomicInteger());
		StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class,
				() -> logs().export(batch(LogRecord.getDefaultInstance())));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
		assertThat(GrpcRetryInfo.retryDelaySeconds(ex)).isEqualTo(300L);
		GrpcRetryInfo.assertNoLegacyRetryAfterTrailer(ex);
	}

	@Test
	void quotaUnavailableExhaustedIsInternalWithoutRetryInfo() {
		assertThat(OtlpGrpcServer.failureStatus(new IngestionDecision.QuotaUnavailableExhausted()).getCode())
				.isEqualTo(Status.Code.INTERNAL);
		assertThat(OtlpGrpcServer.failureStatus(new IngestionDecision.QuotaUnavailableExhausted()).getDescription())
				.contains("retry budget exhausted");
		StatusRuntimeException ex = (StatusRuntimeException) OtlpGrpcServer.asFailure(
				OtlpGrpcServer.failureStatus(new IngestionDecision.QuotaUnavailableExhausted()), null);
		GrpcRetryInfo.assertNoRetryInfo(ex);
	}

	@Test
	void quotaExceededCarriesRetryInfo120() {
		StatusRuntimeException ex = (StatusRuntimeException) OtlpGrpcServer.asFailure(
				OtlpGrpcServer.failureStatus(new IngestionDecision.QuotaExceeded()), 120L);
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
		assertThat(GrpcRetryInfo.retryDelaySeconds(ex)).isEqualTo(120L);
		GrpcRetryInfo.assertNoLegacyRetryAfterTrailer(ex);
	}

	@Test
	void quotaUnavailableCarriesRetryInfo300() {
		StatusRuntimeException ex = (StatusRuntimeException) OtlpGrpcServer.asFailure(
				OtlpGrpcServer.failureStatus(new IngestionDecision.QuotaUnavailable(300)), 300L);
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
		assertThat(GrpcRetryInfo.retryDelaySeconds(ex)).isEqualTo(300L);
		GrpcRetryInfo.assertNoLegacyRetryAfterTrailer(ex);
	}

	@Test
	void applicationBatchTooLargeIsResourceExhausted() {
		AtomicInteger delivered = new AtomicInteger();
		start(TenantRateLimiter.allowAll(), 1024, delivered);
		LogRecord big = LogRecord.newBuilder().setBody(AnyValue.newBuilder().setStringValue("a".repeat(2048))).build();
		StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class, () -> logs().export(batch(big)));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
		assertThat(ex.getStatus().getDescription()).contains("application size limit");
		assertThat(delivered).hasValue(0);
	}

	private void start(TenantRateLimiter limiter, int maxBatchBytes, AtomicInteger delivered) {
		TokenValidator tokens = token -> GOOD.equals(token) ? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(limiter, maxBatchBytes, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> delivered.incrementAndGet(),
				TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpGrpcServer("127.0.0.1", 0, 64 * 1024, logs,
				new ExportTracesService(tokens, (t, p, s) -> {
				}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls));
		server.start();
		channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
	}

	private LogsServiceGrpc.LogsServiceBlockingStub logs() {
		Metadata headers = new Metadata();
		headers.put(AUTHORIZATION, "Bearer " + GOOD);
		return LogsServiceGrpc.newBlockingStub(channel)
				.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
				.withDeadlineAfter(10, TimeUnit.SECONDS);
	}

	private static ExportLogsServiceRequest batch(LogRecord... records) {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder()
						.addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records))))
				.build();
	}
}
