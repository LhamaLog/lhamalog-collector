package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import br.com.lhamalog.collector.logging.CorrelationId;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Correlation id echo / generate / replace on the OTLP/HTTP receiver. */
class OtlpHttpCorrelationIdTest {

	private static final String GOOD = "good-token";
	private final HttpClient client = HttpClient.newHttpClient();
	private OtlpHttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
		MDC.clear();
	}

	@Test
	void echoesValidCorrelationIdHeader() throws Exception {
		start();
		HttpResponse<byte[]> response = post("client-cid-42");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue(CorrelationId.HEADER)).contains("client-cid-42");
		assertThat(MDC.get(CorrelationId.KEY)).isNull();
	}

	@Test
	void replacesInvalidCorrelationIdHeader() throws Exception {
		start();
		HttpResponse<byte[]> response = post("not a valid id!");
		assertThat(response.statusCode()).isEqualTo(200);
		String echoed = response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
		assertThat(echoed).isNotEqualTo("not a valid id!");
		assertThat(CorrelationId.isValid(echoed)).isTrue();
	}

	@Test
	void generatesCorrelationIdWhenHeaderMissing() throws Exception {
		start();
		HttpResponse<byte[]> response = post(null);
		assertThat(response.statusCode()).isEqualTo(200);
		String echoed = response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
		assertThat(CorrelationId.isValid(echoed)).isTrue();
	}

	private void start() {
		var tokens = (br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator) token -> GOOD.equals(token)
				? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), IngestionObservability.NOOP);
		var logs = new ExportLogsService(tokens, (tenant, product, records) -> {
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		var traces = new ExportTracesService(tokens, (tenant, product, spans) -> {
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpHttpServer("127.0.0.1", 0, 64 * 1024, 64 * 1024, logs, traces, HttpLimits.DEFAULT);
		server.start();
	}

	private HttpResponse<byte[]> post(String correlationId) throws Exception {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/v1/logs"))
				.header("Content-Type", "application/x-protobuf")
				.header("Authorization", "Bearer " + GOOD)
				.POST(HttpRequest.BodyPublishers.ofByteArray(batch()));
		if (correlationId != null) {
			request.header(CorrelationId.HEADER, correlationId);
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
