// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** gRPC status classes with fake token catalog and sinks; no Docker needed. */
class OtlpGrpcStatusTest {

	private static final String GOOD = "good-token";

	private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

	private static final Metadata.Key<byte[]> STATUS_DETAILS = Metadata.Key.of("grpc-status-details-bin",
			Metadata.BINARY_BYTE_MARSHALLER);

	private volatile boolean destinationDown;

	private OtlpGrpcServer server;

	private ManagedChannel channel;

	@BeforeEach
	void start() {
		TokenValidator tokens = token -> GOOD.equals(token) ? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> {
			if (destinationDown) {
				throw new SinkUnavailableException("down");
			}
		});
		server = new OtlpGrpcServer("127.0.0.1", 0, 4096, logs, new ExportTracesService(tokens, (t, p, s) -> {
		}));
		server.start();
		channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
	}

	@AfterEach
	void stop() throws InterruptedException {
		channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		server.stop();
	}

	private LogsServiceGrpc.LogsServiceBlockingStub logs(String token) {
		Metadata headers = new Metadata();
		if (token != null) {
			headers.put(AUTHORIZATION, "Bearer " + token);
		}
		return LogsServiceGrpc.newBlockingStub(channel)
			.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
			.withDeadlineAfter(10, TimeUnit.SECONDS);
	}

	private static ExportLogsServiceRequest batch(LogRecord... records) {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records))))
			.build();
	}

	private static StatusRuntimeException failure(Runnable call) {
		return catchThrowableOfType(StatusRuntimeException.class, call::run);
	}

	@Test
	void validBatchIsOkWithoutPartialSuccess() {
		assertThat(logs(GOOD).export(batch(LogRecord.getDefaultInstance())).hasPartialSuccess()).isFalse();
	}

	@Test
	void mixedBatchIsOkWithPartialSuccess() {
		var response = logs(GOOD).export(batch(LogRecord.getDefaultInstance(),
				LogRecord.newBuilder().setTraceId(ByteString.copyFromUtf8("x")).build()));
		assertThat(response.getPartialSuccess().getRejectedLogRecords()).isEqualTo(1);
	}

	@Test
	void missingOrWrongTokenIsUnauthenticated() {
		assertThat(failure(() -> logs(null).export(batch())).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
		assertThat(failure(() -> logs("wrong").export(batch())).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
	}

	@Test
	void allInvalidIsInvalidArgument() {
		StatusRuntimeException ex = failure(() -> logs(GOOD).export(batch(LogRecord.newBuilder().setSeverityNumberValue(30).build())));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(ex.getStatus().getDescription()).startsWith("all 1 log records are invalid");
	}

	@Test
	void messageAboveTheLimitIsResourceExhaustedWithoutRetryInfo() {
		LogRecord big = LogRecord.newBuilder().setBody(AnyValue.newBuilder().setStringValue("a".repeat(8192))).build();
		StatusRuntimeException ex = failure(() -> logs(GOOD).export(batch(big)));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
		assertThat(ex.getTrailers() == null || ex.getTrailers().get(STATUS_DETAILS) == null).isTrue();
	}

	@Test
	void destinationDownIsUnavailable() {
		destinationDown = true;
		assertThat(failure(() -> logs(GOOD).export(batch(LogRecord.getDefaultInstance()))).getStatus().getCode())
			.isEqualTo(Status.Code.UNAVAILABLE);
	}

	@Test
	void metricsServiceIsUnimplemented() {
		StatusRuntimeException ex = failure(() -> MetricsServiceGrpc.newBlockingStub(channel)
			.withDeadlineAfter(10, TimeUnit.SECONDS)
			.export(ExportMetricsServiceRequest.getDefaultInstance()));
		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
	}

}
