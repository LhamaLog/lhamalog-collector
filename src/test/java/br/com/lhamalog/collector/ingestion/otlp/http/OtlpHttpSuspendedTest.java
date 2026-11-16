package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.application.QuotaGate;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import com.google.rpc.Code;
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

/** T029 — OTLP HTTP protobuf/JSON map subscription suspended to 403 / PERMISSION_DENIED. */
class OtlpHttpSuspendedTest {

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
	void suspendedProtobufIs403PermissionDeniedWithoutDelivery() throws Exception {
		AtomicInteger delivered = new AtomicInteger();
		start(delivered);
		HttpResponse<byte[]> response = post("/v1/logs", "application/x-protobuf", batch(), null);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Retry-After")).isEmpty();
		assertThat(Status.parseFrom(response.body()).getCode()).isEqualTo(Code.PERMISSION_DENIED.getNumber());
		assertThat(delivered).hasValue(0);
	}

	@Test
	void suspendedJsonIs403PermissionDeniedWithoutDelivery() throws Exception {
		AtomicInteger delivered = new AtomicInteger();
		start(delivered);
		String json = "{\"resourceLogs\":[{\"scopeLogs\":[{\"logRecords\":[{}]}]}]}";
		HttpResponse<byte[]> response = post("/v1/logs", "application/json", json.getBytes(StandardCharsets.UTF_8),
				null);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
		String body = new String(response.body(), StandardCharsets.UTF_8);
		assertThat(body).contains("\"code\":" + Code.PERMISSION_DENIED.getNumber());
		assertThat(delivered).hasValue(0);
	}

	private void start(AtomicInteger delivered) {
		var tokens = (br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator) token -> GOOD.equals(token)
				? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		QuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> quota = suspendedGate();
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> delivered.incrementAndGet(),
				TimestampWindow.DEFAULT, Clock.systemUTC(), quota, BatchSanitizer.identity(), controls);
		var traces = new ExportTracesService(tokens, (tenant, product, spans) -> {
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpHttpServer("127.0.0.1", 0, 64 * 1024, 64 * 1024, logs, traces, HttpLimits.DEFAULT);
		server.start();
	}

	private static QuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> suspendedGate() {
		return new QuotaGate<>() {
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
