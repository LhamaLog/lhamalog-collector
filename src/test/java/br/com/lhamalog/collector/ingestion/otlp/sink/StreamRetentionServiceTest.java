package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.quota.RetentionCorrelation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamRetentionServiceTest {

	private HttpServer server;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T12:00:00Z"));
	private final Clock clock = new Clock() {
		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	};
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private OpenObserveClient client;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		client = new OpenObserveClient(HttpClient.newHttpClient(), base, "lhamalog", "u", "p");
	}

	@AfterEach
	void stop() {
		RetentionCorrelation.clear();
		server.stop(0);
	}

	@Test
	void appliesOnceThenSkipsSameValue() {
		StreamRetentionService service = syncService();
		service.applyNow("logs", "logs_acme", 7);
		service.applyNow("logs", "logs_acme", 7);
		assertThat(calls).hasValue(1);
		assertThat(lastBody.get()).contains("\"data_retention\":7");
		assertThat(meters.counter(StreamRetentionService.METRIC, "outcome", "applied", "type", "logs").count())
				.isEqualTo(1.0);
	}

	@Test
	void reappliesWhenRetentionChanges() {
		StreamRetentionService service = syncService();
		service.applyNow("logs", "logs_acme", 7);
		service.applyNow("logs", "logs_acme", 30);
		assertThat(calls).hasValue(2);
		assertThat(lastBody.get()).contains("\"data_retention\":30");
	}

	@Test
	void unknownRetentionDoesNotCallClient() {
		StreamRetentionService service = syncService();
		service.ensureAfterIngestAsync("logs", "logs_acme");
		assertThat(calls).hasValue(0);
	}

	@Test
	void failureUsesBackoffThenRetries() {
		status.set(500);
		StreamRetentionService service = syncService();
		service.applyNow("logs", "logs_acme", 7);
		service.applyNow("logs", "logs_acme", 7);
		assertThat(calls).hasValue(1);
		assertThat(meters.counter(StreamRetentionService.METRIC, "outcome", "failed", "type", "logs").count())
				.isEqualTo(1.0);

		status.set(200);
		now.set(now.get().plusSeconds(61));
		service.applyNow("logs", "logs_acme", 7);
		assertThat(calls).hasValue(2);
	}

	@Test
	void ensureAfterIngestUsesCorrelation() {
		RetentionCorrelation.set(15);
		StreamRetentionService service = syncService();
		service.ensureAfterIngestAsync("traces", "traces_t1");
		assertThat(calls).hasValue(1);
		assertThat(lastBody.get()).contains("\"data_retention\":15");
	}

	@Test
	void fullOrStoppedQueueDropsApplyWithoutThrowingAndRetriesLater() {
		RetentionCorrelation.set(7);
		java.util.concurrent.ThreadPoolExecutor stopped = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
				TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1));
		stopped.shutdown();
		StreamRetentionService dropping = new StreamRetentionService(client, clock, meters, stopped);
		dropping.ensureAfterIngestAsync("logs", "logs_acme");
		assertThat(calls).hasValue(0);
		assertThat(meters.counter(StreamRetentionService.METRIC, "outcome", "dropped", "type", "logs").count())
				.isEqualTo(1.0);

		syncService().ensureAfterIngestAsync("logs", "logs_acme");
		assertThat(calls).hasValue(1);
	}

	@Test
	void shutdownStopsTheExecutor() {
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		new StreamRetentionService(client, clock, meters, executor).shutdown();
		assertThat(executor.isShutdown()).isTrue();
	}

	private StreamRetentionService syncService() {
		return new StreamRetentionService(client, clock, meters, new AbstractExecutorService() {
			@Override
			public void shutdown() {
			}

			@Override
			public List<Runnable> shutdownNow() {
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

	private void respond(HttpExchange exchange) throws java.io.IOException {
		calls.incrementAndGet();
		lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status.get(), ok.length);
		exchange.getResponseBody().write(ok);
		exchange.close();
	}
}
