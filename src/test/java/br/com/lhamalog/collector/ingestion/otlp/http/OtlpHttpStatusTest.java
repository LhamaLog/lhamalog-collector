// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP status classes with fake token catalog and sinks; no Docker needed. */
class OtlpHttpStatusTest {

	private static final String GOOD = "good-token";

	private static final int LIMIT = 4096;

	private final HttpClient client = HttpClient.newHttpClient();

	private final List<LogRecord> delivered = new ArrayList<>();

	private volatile boolean destinationDown;

	private OtlpHttpServer server;

	@BeforeEach
	void start() {
		var tokens = (br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator) token -> GOOD.equals(token)
				? new TokenValidationResult.Valid("tenant-1", "product-a") : new TokenValidationResult.Unauthorized();
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> {
			if (destinationDown) {
				throw new SinkUnavailableException("down", 9, null);
			}
			delivered.addAll(records);
		});
		var traces = new ExportTracesService(tokens, (tenant, product, spans) -> {
		});
		server = new OtlpHttpServer("127.0.0.1", 0, LIMIT, logs, traces);
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop();
	}

	private static byte[] batch(LogRecord... records) {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records))))
			.build()
			.toByteArray();
	}

	private HttpResponse<byte[]> post(String path, String token, String contentType, byte[] body, String encoding) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
			.header("Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (encoding != null) {
			request.header("Content-Encoding", encoding);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static Status status(HttpResponse<byte[]> response) throws Exception {
		return Status.parseFrom(response.body());
	}

	@Test
	void validProtobufBatchIs200WithoutPartialSuccess() throws Exception {
		HttpResponse<byte[]> response = post("/v1/logs", GOOD, "application/x-protobuf", batch(LogRecord.getDefaultInstance()), null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).contains("application/x-protobuf");
		assertThat(ExportLogsServiceResponse.parseFrom(response.body()).hasPartialSuccess()).isFalse();
		assertThat(delivered).hasSize(1);
	}

	@Test
	void missingOrWrongTokenIs401WithBearerChallenge() throws Exception {
		for (String token : new String[] { null, "wrong" }) {
			HttpResponse<byte[]> response = post("/v1/logs", token, "application/x-protobuf", batch(LogRecord.getDefaultInstance()), null);
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
			assertThat(status(response).getMessage()).isEqualTo("missing or invalid bearer token");
		}
		assertThat(delivered).isEmpty();
	}

	@Test
	void malformedBodyAndAllInvalidAre400() throws Exception {
		HttpResponse<byte[]> garbage = post("/v1/logs", GOOD, "application/x-protobuf", new byte[] { (byte) 0xff, 1, 2 }, null);
		assertThat(garbage.statusCode()).isEqualTo(400);
		assertThat(status(garbage).getMessage()).contains("not a valid OTLP protobuf");
		HttpResponse<byte[]> allInvalid = post("/v1/logs", GOOD, "application/x-protobuf",
				batch(LogRecord.newBuilder().setSeverityNumberValue(99).build()), null);
		assertThat(allInvalid.statusCode()).isEqualTo(400);
		assertThat(status(allInvalid).getMessage()).startsWith("all 1 log records are invalid");
	}

	@Test
	void mixedBatchIs200WithPartialSuccess() throws Exception {
		HttpResponse<byte[]> response = post("/v1/logs", GOOD, "application/x-protobuf",
				batch(LogRecord.getDefaultInstance(), LogRecord.newBuilder().setSpanId(ByteString.copyFromUtf8("x")).build()), null);
		assertThat(response.statusCode()).isEqualTo(200);
		var partial = ExportLogsServiceResponse.parseFrom(response.body()).getPartialSuccess();
		assertThat(partial.getRejectedLogRecords()).isEqualTo(1);
		assertThat(partial.getErrorMessage()).isNotBlank();
	}

	@Test
	void bodyAboveTheLimitAfterDecompressionIs413() throws Exception {
		byte[] big = batch(LogRecord.newBuilder()
			.setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("a".repeat(LIMIT * 4)))
			.build());
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
			gzip.write(big);
		}
		assertThat(compressed.size()).isLessThan(LIMIT);
		HttpResponse<byte[]> response = post("/v1/logs", GOOD, "application/x-protobuf", compressed.toByteArray(), "gzip");
		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(status(response).getMessage()).contains("exceeds the limit");
	}

	@Test
	void gzipBodyIsAccepted() throws Exception {
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
			gzip.write(batch(LogRecord.getDefaultInstance()));
		}
		assertThat(post("/v1/logs", GOOD, "application/x-protobuf", compressed.toByteArray(), "gzip").statusCode()).isEqualTo(200);
	}

	@Test
	void unsupportedEncodingIs415() throws Exception {
		assertThat(post("/v1/logs", GOOD, "application/x-protobuf", batch(), "br").statusCode()).isEqualTo(415);
		assertThat(post("/v1/logs", GOOD, "text/plain", batch(), null).statusCode()).isEqualTo(415);
	}

	@Test
	void destinationDownIs503WithRetryAfter() throws Exception {
		destinationDown = true;
		HttpResponse<byte[]> response = post("/v1/logs", GOOD, "application/x-protobuf", batch(LogRecord.getDefaultInstance()), null);
		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.headers().firstValue("Retry-After")).contains("9");
		assertThat(status(response).getMessage()).isEqualTo("destination unavailable, retry later");
	}

	@Test
	void metricsAndUnknownPathsAre404() throws Exception {
		assertThat(post("/v1/metrics", GOOD, "application/x-protobuf", batch(), null).statusCode()).isEqualTo(404);
		assertThat(post("/v1/other", GOOD, "application/x-protobuf", batch(), null).statusCode()).isEqualTo(404);
	}

	@Test
	void otherMethodIs405WithAllowPost() throws Exception {
		HttpResponse<byte[]> response = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/v1/logs")).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(405);
		assertThat(response.headers().firstValue("Allow")).contains("POST");
	}

	@Test
	void jsonWithHexIdsAndIntegerEnumsIsAcceptedAndAnsweredInJson() throws Exception {
		String json = """
				{"resourceLogs":[{"scopeLogs":[{"logRecords":[{"timeUnixNano":"1700000000000000000","severityNumber":9,
				"traceId":"5b8efff798038103d269b633813fc60c","spanId":"eee19b7ec3c1b174","body":{"stringValue":"hello"}}]}]}]}
				""";
		HttpResponse<byte[]> response = post("/v1/logs", GOOD, "application/json", json.getBytes(StandardCharsets.UTF_8), null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{}");
		assertThat(delivered.getFirst().getTraceId().size()).isEqualTo(16);
		assertThat(delivered.getFirst().getSpanId().size()).isEqualTo(8);
	}

	@Test
	void tracesPathUsesTraceResponse() throws Exception {
		HttpResponse<byte[]> response = post("/v1/traces", GOOD, "application/x-protobuf", new byte[0], null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(ExportTraceServiceResponse.parseFrom(response.body()).hasPartialSuccess()).isFalse();
	}

}
