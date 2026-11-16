package br.com.lhamalog.collector.ingestion.otlp.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.application.QuotaGate;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
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
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T029 — OTLP gRPC maps subscription suspended to PERMISSION_DENIED. */
class OtlpGrpcSuspendedTest {

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
	void suspendedIsPermissionDeniedWithoutRetryAfterOrDelivery() {
		AtomicInteger delivered = new AtomicInteger();
		start(delivered);
		StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class,
				() -> logs().export(batch(LogRecord.getDefaultInstance())));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
		assertThat(ex.getStatus().getDescription()).contains("subscription suspended");
		GrpcRetryInfo.assertNoRetryInfo(ex);
		assertThat(delivered).hasValue(0);
	}

	@Test
	void failureStatusMapsSuspendedToPermissionDenied() {
		assertThat(OtlpGrpcServer.failureStatus(new IngestionDecision.Suspended()).getCode())
				.isEqualTo(Status.Code.PERMISSION_DENIED);
	}

	private void start(AtomicInteger delivered) {
		TokenValidator tokens = token -> GOOD.equals(token) ? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		QuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenantId, String productId, ExportLogsServiceRequest request,
					List<Scoped<LogRecord>> accepted) {
				return new Reservation(Reservation.Outcome.SUSPENDED, null);
			}

			@Override
			public void confirm(String reservationId) {
				throw new AssertionError("confirm must not run for suspended");
			}

			@Override
			public void release(String reservationId) {
				throw new AssertionError("release must not run for suspended");
			}
		};
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> delivered.incrementAndGet(),
				TimestampWindow.DEFAULT, Clock.systemUTC(), quota, BatchSanitizer.identity(), controls);
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
