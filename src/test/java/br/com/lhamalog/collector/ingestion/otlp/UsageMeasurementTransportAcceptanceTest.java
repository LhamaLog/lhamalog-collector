package br.com.lhamalog.collector.ingestion.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.TracesSink;
import br.com.lhamalog.collector.usage.domain.CanonicalOtlpMeasurement;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class UsageMeasurementTransportAcceptanceTest {

	@Test
	void logsHaveTheSameCanonicalBytesOverHttpProtobufJsonGzipAndGrpc() throws Exception {
		List<Long> bytes = new ArrayList<>();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder()
				.addLogRecords(LogRecord.newBuilder().setSeverityNumberValue(9).setBody(
					io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("same"))))).build();
		withServers(capturingLogs(bytes), noopTraces(),
			(http, grpc) -> {
				post(http, "/v1/logs", "application/x-protobuf", request.toByteArray(), false);
				post(http, "/v1/logs", "application/json", JsonFormat.printer().print(request).getBytes(), false);
				post(http, "/v1/logs", "application/x-protobuf", request.toByteArray(), true);
				ManagedChannel channel = OtlpAcceptanceSupport.channel(grpc);
				try {
					LogsServiceGrpc.newBlockingStub(channel).withInterceptors(OtlpAcceptanceSupport.bearer("test"))
						.withDeadlineAfter(5, TimeUnit.SECONDS).export(request);
				}
				finally { channel.shutdownNow(); }
			});
		assertThat(bytes).hasSize(4).allMatch(value -> value.equals(bytes.getFirst()));
	}

	@Test
	void tracesHaveTheSameCanonicalBytesOverHttpProtobufJsonGzipAndGrpc() throws Exception {
		List<Long> bytes = new ArrayList<>();
		byte[] traceId = new byte[16];
		byte[] spanId = new byte[8];
		traceId[0] = 1;
		spanId[0] = 1;
		Span span = Span.newBuilder().setTraceId(com.google.protobuf.ByteString.copyFrom(traceId))
			.setSpanId(com.google.protobuf.ByteString.copyFrom(spanId)).setName("same").build();
		ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build();
		withServers(noopLogs(), capturingTraces(bytes), (http, grpc) -> {
				post(http, "/v1/traces", "application/x-protobuf", request.toByteArray(), false);
				post(http, "/v1/traces", "application/json", JsonFormat.printer().print(request).getBytes(), false);
				post(http, "/v1/traces", "application/x-protobuf", request.toByteArray(), true);
				ManagedChannel channel = OtlpAcceptanceSupport.channel(grpc);
				try {
					TraceServiceGrpc.newBlockingStub(channel).withInterceptors(OtlpAcceptanceSupport.bearer("test"))
						.withDeadlineAfter(5, TimeUnit.SECONDS).export(request);
				}
				finally { channel.shutdownNow(); }
			});
		assertThat(bytes).hasSize(4).allMatch(value -> value.equals(bytes.getFirst()));
	}

	private static void withServers(LogsSink logs, TracesSink traces, Ports test) throws Exception {
		ExportLogsService logService = new ExportLogsService(token -> new TokenValidationResult.Valid("tenant", "product"), logs);
		ExportTracesService traceService = new ExportTracesService(token -> new TokenValidationResult.Valid("tenant", "product"), traces);
		OtlpHttpServer http = new OtlpHttpServer("127.0.0.1", 0, 1 << 20, logService, traceService);
		OtlpGrpcServer grpc = new OtlpGrpcServer("127.0.0.1", 0, 1 << 20, logService, traceService);
		http.start();
		grpc.start();
		try { test.run(http.getPort(), grpc.getPort()); } finally { grpc.stop(); http.stop(); }
	}

	private static LogsSink capturingLogs(List<Long> bytes) {
		return new LogsSink() {
			@Override public void deliver(String tenant, String product, List<br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<LogRecord>> accepted) { }
			@Override public void deliver(String tenant, String product, ExportLogsServiceRequest decoded,
					List<br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<LogRecord>> accepted) {
				bytes.add(CanonicalOtlpMeasurement.logs(decoded, accepted).serializedBytes());
			}
		};
	}

	private static LogsSink noopLogs() {
		return (tenant, product, accepted) -> { };
	}

	private static TracesSink capturingTraces(List<Long> bytes) {
		return new TracesSink() {
			@Override public void deliver(String tenant, String product, List<br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<Span>> accepted) { }
			@Override public void deliver(String tenant, String product, ExportTraceServiceRequest decoded,
					List<br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<Span>> accepted) {
				bytes.add(CanonicalOtlpMeasurement.traces(decoded, accepted).serializedBytes());
			}
		};
	}

	private static TracesSink noopTraces() {
		return (tenant, product, accepted) -> { };
	}

	private static void post(int port, String path, String type, byte[] value, boolean gzip) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.header("Authorization", "Bearer test").header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(gzip(value, gzip)));
		if (gzip) request.header("Content-Encoding", "gzip");
		assertThat(HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
	}

	private static byte[] gzip(byte[] value, boolean compress) throws Exception {
		if (!compress) return value;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(value); }
		return output.toByteArray();
	}

	@FunctionalInterface private interface Ports { void run(int http, int grpc) throws Exception; }
}
