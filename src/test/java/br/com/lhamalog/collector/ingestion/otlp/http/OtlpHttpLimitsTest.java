// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Protection limits of the JDK HTTP server (T046, FR-025). */
class OtlpHttpLimitsTest {

	@Test
	void invalidLimitsFailFast() {
		assertThatThrownBy(() -> new HttpLimits(0, 10, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-concurrent-requests");
		assertThatThrownBy(() -> new HttpLimits(1, 10, Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(1), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("read-timeout");
	}

	@Test
	void serverPropertiesAreSetFromLimitsWithoutOverridingExplicitValues() {
		String key = "sun.net.httpserver.maxReqTime";
		String previous = System.getProperty(key);
		Map<String, String> effective = OtlpHttpServer.applyServerProperties(HttpLimits.DEFAULT);
		assertThat(effective).containsKeys("sun.net.httpserver.maxReqTime", "sun.net.httpserver.maxRspTime",
				"sun.net.httpserver.idleInterval", "sun.net.httpserver.maxIdleConnections",
				"sun.net.httpserver.maxConnections");
		assertThat(System.getProperty(key)).isNotNull();
		if (previous != null) {
			assertThat(effective.get(key)).isEqualTo(previous);
		}
		// an explicit value already present is kept
		System.setProperty("sun.net.httpserver.maxIdleConnections", "7");
		try {
			assertThat(OtlpHttpServer.applyServerProperties(HttpLimits.DEFAULT))
				.containsEntry("sun.net.httpserver.maxIdleConnections", "7");
		}
		finally {
			System.setProperty("sun.net.httpserver.maxIdleConnections",
					Integer.toString(HttpLimits.DEFAULT.maxIdleConnections()));
		}
	}

	@Test
	void requestsAboveTheConcurrencyLimitGet503WithRetryAfter() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TokenValidator blocking = token -> {
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return new TokenValidationResult.Valid("t", "p");
		};
		var logs = new ExportLogsService(blocking, (tenant, product, records) -> {
		});
		var traces = new ExportTracesService(blocking, (tenant, product, spans) -> {
		});
		HttpLimits limits = new HttpLimits(1, 100, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30), 10);
		OtlpHttpServer server = new OtlpHttpServer("127.0.0.1", 0, 4096, logs, traces, limits);
		server.start();
		try {
			HttpClient client = HttpClient.newHttpClient();
			byte[] body = ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.getDefaultInstance())))
				.build()
				.toByteArray();
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/v1/logs"))
				.header("Content-Type", "application/x-protobuf")
				.header("Authorization", "Bearer x")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
			CompletableFuture<HttpResponse<byte[]>> first = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			HttpResponse<byte[]> second = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			assertThat(second.statusCode()).isEqualTo(503);
			assertThat(second.headers().firstValue("Retry-After")).isPresent();
			release.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
			assertThat(client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
		}
		finally {
			release.countDown();
			server.stop();
		}
	}

}
