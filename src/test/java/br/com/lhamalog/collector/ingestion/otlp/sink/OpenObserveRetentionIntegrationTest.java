package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.lhamalog.collector.TestcontainersConfiguration;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.quota.RetentionCorrelation;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLT-185: per-tenant streams get {@code data_retention} from reservation; settings failure does not refuse the
 * batch.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OpenObserveRetentionIntegrationTest {

	@Autowired
	Environment env;

	private final JsonMapper json = JsonMapper.builder().build();

	@AfterEach
	void clear() {
		RetentionCorrelation.clear();
	}

	@Test
	void twoTenantsReceiveDistinctStreamsAndMatchingRetention() throws Exception {
		OpenObserveClient client = client();
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		StreamRetentionService retention = syncRetention(client, meters);
		OpenObserveLogsSink sink = new OpenObserveLogsSink(client, "logs", Clock.systemUTC(), retention);

		String tenantA = "tenant-a-" + UUID.randomUUID().toString().substring(0, 8);
		String tenantB = "tenant-b-" + UUID.randomUUID().toString().substring(0, 8);
		String streamA = TenantStreamNames.resolve("logs", tenantA);
		String streamB = TenantStreamNames.resolve("logs", tenantB);
		assertThat(streamA).isNotEqualTo(streamB);

		RetentionCorrelation.set(7);
		sink.store(tenantA, "prod-a", List.of(Scoped.of(log("a-" + UUID.randomUUID()))));
		RetentionCorrelation.set(30);
		sink.store(tenantB, "prod-b", List.of(Scoped.of(log("b-" + UUID.randomUUID()))));

		assertThat(awaitRetention(streamA, 7)).isEqualTo(7);
		assertThat(awaitRetention(streamB, 30)).isEqualTo(30);
		assertThat(meters.counter(StreamRetentionService.METRIC, "outcome", "applied", "type", "logs").count())
				.isGreaterThanOrEqualTo(2.0);
	}

	@Test
	void settingsFailureStillAcceptsBatch() throws Exception {
		HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		dead.createContext("/", exchange -> {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
		});
		dead.start();
		try {
			OpenObserveClient deadClient = new OpenObserveClient(HttpClient.newHttpClient(),
					"http://127.0.0.1:" + dead.getAddress().getPort(), "lhamalog", "u", "p");
			OpenObserveClient live = client();
			StreamRetentionService badRetention = syncRetention(deadClient, new SimpleMeterRegistry());
			OpenObserveLogsSink sink = new OpenObserveLogsSink(live, "logs", Clock.systemUTC(), badRetention);
			RetentionCorrelation.set(15);
			String tenant = "tenant-fail-" + UUID.randomUUID().toString().substring(0, 8);
			assertThatCode(() -> sink.store(tenant, "p", List.of(Scoped.of(log("fail-" + UUID.randomUUID())))))
					.doesNotThrowAnyException();
		}
		finally {
			dead.stop(0);
		}
	}

	private OpenObserveClient client() {
		return new OpenObserveClient(HttpClient.newHttpClient(),
				env.getRequiredProperty("lhamalog.otlp.openobserve.url"),
				env.getProperty("lhamalog.otlp.openobserve.organization", "lhamalog"),
				env.getRequiredProperty("lhamalog.otlp.openobserve.username"),
				env.getRequiredProperty("lhamalog.otlp.openobserve.password"));
	}

	private static StreamRetentionService syncRetention(OpenObserveClient client, SimpleMeterRegistry meters) {
		return new StreamRetentionService(client, Clock.systemUTC(), meters, new AbstractExecutorService() {
			@Override
			public void shutdown() {
			}

			@Override
			public java.util.List<Runnable> shutdownNow() {
				return List.of();
			}

			@Override
			public boolean isShutdown() {
				return false;
			}

			@Override
			public boolean isTerminated() {
				return false;
			}

			@Override
			public boolean awaitTermination(long timeout, TimeUnit unit) {
				return true;
			}

			@Override
			public void execute(Runnable command) {
				command.run();
			}
		});
	}

	private int awaitRetention(String stream, int expected) throws Exception {
		long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		Integer last = null;
		while (System.nanoTime() < end) {
			last = readRetention(stream);
			if (last != null && last == expected) {
				return last;
			}
			Thread.sleep(1000);
		}
		assertThat(last).as("data_retention for %s", stream).isEqualTo(expected);
		return last;
	}

	private Integer readRetention(String stream) throws Exception {
		String user = env.getRequiredProperty("lhamalog.otlp.openobserve.username");
		String pass = env.getRequiredProperty("lhamalog.otlp.openobserve.password");
		String org = env.getProperty("lhamalog.otlp.openobserve.organization", "lhamalog");
		String url = env.getRequiredProperty("lhamalog.otlp.openobserve.url") + "/api/" + org + "/streams/" + stream
				+ "/schema?type=logs";
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Authorization",
					"Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8)))
			.GET()
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			return null;
		}
		JsonNode tree = json.readTree(response.body());
		JsonNode settings = tree.path("settings");
		if (settings.isMissingNode() || settings.isNull()) {
			settings = tree.path("stream").path("settings");
		}
		JsonNode retention = settings.path("data_retention");
		if (retention.isMissingNode() || retention.isNull() || !retention.isNumber()) {
			return null;
		}
		return retention.asInt();
	}

	private static LogRecord log(String body) {
		return LogRecord.newBuilder()
			.setTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
			.setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue(body))
			.build();
	}
}
