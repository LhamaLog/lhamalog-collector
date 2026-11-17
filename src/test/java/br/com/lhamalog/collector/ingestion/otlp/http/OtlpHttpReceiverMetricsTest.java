package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.BatchSanitizer;
import br.com.lhamalog.collector.ingestion.otlp.application.CoreUnavailableAttemptTracker;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionControls;
import br.com.lhamalog.collector.ingestion.otlp.application.IngestionObservability;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** LLT-187 review: metrics recorded end to end from the real HTTP receiver (protocol tag, partial outcome). */
class OtlpHttpReceiverMetricsTest {

	private static final String GOOD = "good-token";
	private final HttpClient client = HttpClient.newHttpClient();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private OtlpHttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
	}

	@Test
	void acceptedBatchCountsRecordsBytesAndLatencyWithHttpProtocol() throws Exception {
		start((tenant, product, records) -> {
		});

		HttpResponse<byte[]> response = post(batch(2));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(records("logs", "http")).isEqualTo(2.0d);
		assertThat(meters.find("lhamalog.ingestion.accepted.bytes").tag("signal", "logs").tag("protocol", "http")
				.counter().count()).isPositive();
		assertThat(latencyCount("accepted")).isEqualTo(1L);
	}

	@Test
	void destinationPartialAcceptanceCountsStoredRecordsAndTagsPartial() throws Exception {
		start((tenant, product, records) -> {
			throw new SinkRejectedException(1, records.size() - 1, "one record refused");
		});

		HttpResponse<byte[]> response = post(batch(3));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(records("logs", "http")).isEqualTo(2.0d);
		assertThat(latencyCount("partial")).isEqualTo(1L);
		assertThat(currentLatencyCount("accepted")).isZero();
	}

	private double records(String signal, String protocol) {
		var counter = meters.find("lhamalog.ingestion.accepted.records").tag("signal", signal)
				.tag("protocol", protocol).counter();
		return counter == null ? 0d : counter.count();
	}

	/**
	 * The receiver records latency after the response is written, so the client can see the response first.
	 * Waits briefly for the timer instead of asserting a race.
	 */
	private long latencyCount(String outcome) throws InterruptedException {
		long deadline = System.nanoTime() + 2_000_000_000L;
		long count = currentLatencyCount(outcome);
		while (count == 0L && System.nanoTime() < deadline) {
			Thread.sleep(20);
			count = currentLatencyCount(outcome);
		}
		return count;
	}

	private long currentLatencyCount(String outcome) {
		var timer = meters.find("lhamalog.ingestion.latency").tag("signal", "logs").tag("protocol", "http")
				.tag("outcome", outcome).timer();
		return timer == null ? 0L : timer.count();
	}

	private void start(LogsSink sink) {
		TokenValidator tokens = token -> GOOD.equals(token) ? new TokenValidationResult.Valid("tenant-1", "product-a")
				: new TokenValidationResult.Unauthorized();
		IngestionObservability observability = new IngestionObservability(meters);
		IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5, 300,
				CoreUnavailableAttemptTracker.alwaysRetryable(300), observability);
		var logs = new ExportLogsService(tokens, sink, TimestampWindow.DEFAULT, Clock.systemUTC(), null,
				BatchSanitizer.identity(), controls);
		var traces = new ExportTracesService(tokens, (tenant, product, spans) -> {
		}, TimestampWindow.DEFAULT, Clock.systemUTC(), null, BatchSanitizer.identity(), controls);
		server = new OtlpHttpServer("127.0.0.1", 0, 64 * 1024, 64 * 1024, logs, traces, HttpLimits.DEFAULT,
				observability);
		server.start();
	}

	private HttpResponse<byte[]> post(byte[] body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/v1/logs"))
				.header("Content-Type", "application/x-protobuf")
				.header("Authorization", "Bearer " + GOOD)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static byte[] batch(int count) {
		long now = Instant.now().toEpochMilli() * 1_000_000L;
		ScopeLogs.Builder scope = ScopeLogs.newBuilder();
		for (int i = 0; i < count; i++) {
			scope.addLogRecords(LogRecord.newBuilder().setTimeUnixNano(now).setObservedTimeUnixNano(now)
					.setBody(AnyValue.newBuilder().setStringValue("metric-test-" + i)));
		}
		return ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(scope))
				.build().toByteArray();
	}
}
