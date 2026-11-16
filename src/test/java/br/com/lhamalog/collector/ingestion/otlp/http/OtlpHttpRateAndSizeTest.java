package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import com.google.rpc.Status;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T011 / T019 / T041 — HTTP protobuf/JSON rate (Retry-After 5 vs 300) and batch size refusals. */
class OtlpHttpRateAndSizeTest {

	private static final String GOOD = "good-token";
	private final HttpClient client = HttpClient.newHttpClient();
	private OtlpHttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
	}

	@Test
	void rateLimitedProtobufIs429WithRetryAfter5() throws Exception {
		AtomicInteger delivered = new AtomicInteger();
		start(tenant -> TenantRateLimiter.Verdict.LIMITED, 64 * 1024, delivered);
		HttpResponse<byte[]> response = post("/v1/logs", "application/x-protobuf", batch(), null);
		assertThat(response.statusCode()).isEqualTo(429);
		assertThat(response.headers().firstValue("Retry-After")).contains("5");
		assertThat(Status.parseFrom(response.body()).getCode()).isEqualTo(com.google.rpc.Code.RESOURCE_EXHAUSTED.getNumber());
		assertThat(delivered).hasValue(0);
	}

	@Test
	void rateBlockedProtobufIs429WithRetryAfter300() throws Exception {
		start(tenant -> TenantRateLimiter.Verdict.BLOCKED, 64 * 1024, new AtomicInteger());
		HttpResponse<byte[]> response = post("/v1/logs", "application/x-protobuf", batch(), null);
		assertThat(response.statusCode()).isEqualTo(429);
		assertThat(response.headers().firstValue("Retry-After")).contains("300");
	}

	@Test
	void rateLimitedJsonIs429WithRetryAfter5() throws Exception {
		start(tenant -> TenantRateLimiter.Verdict.LIMITED, 64 * 1024, new AtomicInteger());
		String json = "{\"resourceLogs\":[{\"scopeLogs\":[{\"logRecords\":[{}]}]}]}";
		HttpResponse<byte[]> response = post("/v1/logs", "application/json", json.getBytes(StandardCharsets.UTF_8),
				null);
		assertThat(response.statusCode()).isEqualTo(429);
		assertThat(response.headers().firstValue("Retry-After")).contains("5");
	}

	@Test
	void quotaUnavailableExhaustedIs500WithoutRetryAfter() throws Exception {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(
				new IngestionDecision.QuotaUnavailableExhausted(), OtlpHttpStatusMapper.Signal.LOGS);
		assertThat(outcome.status()).isEqualTo(500);
		assertThat(outcome.headers()).doesNotContainKey("Retry-After");
		assertThat(((Status) outcome.body()).getMessage()).contains("retry budget exhausted");
	}

	@Test
	void applicationBatchTooLargeIs413WithoutDelivery() throws Exception {
		AtomicInteger delivered = new AtomicInteger();
		start(TenantRateLimiter.allowAll(), 1024, delivered);
		byte[] body = new byte[2048];
		HttpResponse<byte[]> response = post("/v1/logs", "application/x-protobuf", body, null);
		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(delivered).hasValue(0);
	}

	private void start(TenantRateLimiter limiter, int maxBatchBytes, AtomicInteger delivered) {
		var tokens = (br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator) token -> GOOD.equals(token)
				? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(limiter, maxBatchBytes, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> delivered.incrementAndGet(),
				TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		var traces = new ExportTracesService(tokens, (tenant, product, spans) -> {
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpHttpServer("127.0.0.1", 0, 64 * 1024, maxBatchBytes, logs, traces, HttpLimits.DEFAULT);
		server.start();
	}

	private HttpResponse<byte[]> post(String path, String contentType, byte[] body, String encoding) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
				.header("Content-Type", contentType)
				.header("Authorization", "Bearer " + GOOD)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (encoding != null) {
			request.header("Content-Encoding", encoding);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static byte[] batch() {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder()
						.addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(LogRecord.getDefaultInstance()))))
				.build()
				.toByteArray();
	}
}
