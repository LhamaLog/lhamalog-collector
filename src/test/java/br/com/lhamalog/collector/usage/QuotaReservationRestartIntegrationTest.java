package br.com.lhamalog.collector.usage;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.RecordIds;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.quota.CanonicalQuotaGate;
import br.com.lhamalog.collector.quota.HttpQuotaReservationClient;
import br.com.lhamalog.collector.quota.MongoRefundOutboxRepository;
import br.com.lhamalog.collector.quota.QuotaProperties;
import br.com.lhamalog.collector.quota.QuotaReservationClient;
import br.com.lhamalog.collector.quota.RefundOutboxItem;
import br.com.lhamalog.collector.quota.RefundOutboxState;
import br.com.lhamalog.collector.quota.RefundOutboxWorker;
import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.MongoUsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * T040: restart/recovery between reserve, confirm/publish/release, and durable refund outbox.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuotaReservationRestartIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Container
	static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static MongoClient mongoClient;
	private static MongoTemplate mongoTemplate;
	private static MongoUsageOutboxRepository usageOutbox;
	private static MongoRefundOutboxRepository refundOutbox;

	private HttpServer core;
	private final List<String> coreCalls = new CopyOnWriteArrayList<>();
	private final List<String> reservationIds = new CopyOnWriteArrayList<>();
	private final AtomicBoolean failConfirm = new AtomicBoolean();
	private final AtomicInteger refundStatus = new AtomicInteger(204);
	private final List<String> deliveries = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void startMongo() {
		mongoClient = MongoClients.create(mongo.getReplicaSetUrl());
		mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, "quota_restart"));
		usageOutbox = new MongoUsageOutboxRepository(mongoTemplate);
		refundOutbox = new MongoRefundOutboxRepository(mongoTemplate);
	}

	@AfterAll
	static void stopMongo() {
		mongoClient.close();
	}

	@BeforeEach
	void startCore() throws Exception {
		coreCalls.clear();
		reservationIds.clear();
		deliveries.clear();
		failConfirm.set(false);
		refundStatus.set(204);
		core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		core.createContext("/internal/usage-reservations", this::handleCore);
		core.start();
	}

	@AfterEach
	void stopCore() {
		core.stop(0);
	}

	@Test
	void crashBetweenReserveAndConfirmReusesDeterministicReservationIdOnExporterRetry() {
		failConfirm.set(true);
		HttpQuotaReservationClient client = reservationClient();
		CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> gate = new CanonicalQuotaGate<>(client);
		ExportLogsService service = logsService(gate);
		ExportLogsServiceRequest request = logsBatch("restart-reserve");

		assertThat(service.export("bearer", () -> request)).isEqualTo(new IngestionDecision.QuotaUnavailable());
		assertThat(coreCalls).containsExactly("reserve", "confirm");
		assertThat(deliveries).isEmpty();
		assertThat(reservationIds).hasSize(1);
		String reservedWhileCrashed = reservationIds.getFirst();

		failConfirm.set(false);
		assertThat(service.export("bearer", () -> request)).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(coreCalls).containsExactly("reserve", "confirm", "reserve", "confirm");
		assertThat(reservationIds).containsExactly(reservedWhileCrashed, reservedWhileCrashed);
		assertThat(deliveries).containsExactly("logs");
	}

	@Test
	void pendingRefundSurvivesRestartAndFreshWorkerCompletesIt() {
		Instant enqueuedAt = NOW.minusSeconds(30);
		refundOutbox.enqueue("quota:restart-refund-1", "SINK_UNAVAILABLE", enqueuedAt);
		assertThat(refundOutbox.ready(NOW)).extracting(RefundOutboxItem::reservationId)
				.contains("quota:restart-refund-1");

		MongoRefundOutboxRepository restartedOutbox = new MongoRefundOutboxRepository(
				new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, "quota_restart")));
		List<RefundOutboxItem> surviving = restartedOutbox.ready(NOW);
		assertThat(surviving).extracting(RefundOutboxItem::reservationId).contains("quota:restart-refund-1");
		assertThat(surviving).extracting(RefundOutboxItem::state).containsOnly(RefundOutboxState.PENDING);

		AtomicInteger refunds = new AtomicInteger();
		QuotaReservationClient refundClient = new QuotaReservationClient() {
			@Override
			public ReservationOutcome reserve(ReservationRequest request) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}

			@Override
			public int refund(String reservationId) {
				refunds.incrementAndGet();
				assertThat(reservationId).isEqualTo("quota:restart-refund-1");
				return 204;
			}
		};
		RefundOutboxWorker freshWorker = new RefundOutboxWorker(restartedOutbox, refundClient,
				new QuotaProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(250),
						Duration.ofSeconds(2)),
				CLOCK);
		freshWorker.runOnce();

		assertThat(refunds).hasValue(1);
		assertThat(restartedOutbox.ready(NOW)).extracting(RefundOutboxItem::reservationId)
				.doesNotContain("quota:restart-refund-1");
	}

	private ExportLogsService logsService(CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> gate) {
		UsageFactRecorder recorder = new UsageFactRecorder(usageOutbox, CLOCK);
		PublishingLogsSink sink = new PublishingLogsSink(logsStore(), (tenant, product, batch, records) -> {
		}, new UsageFactFactory(CLOCK), recorder, (tenant, product, batch, records, key) -> {
			deliveries.add("logs");
			return "DONE:logs";
		});
		return new ExportLogsService(token -> new TokenValidationResult.Valid("tenant", "product"), sink,
				TimestampWindow.DEFAULT, CLOCK, gate);
	}

	private HttpQuotaReservationClient reservationClient() {
		return new HttpQuotaReservationClient(HttpClient.newHttpClient(),
				URI.create("http://127.0.0.1:" + core.getAddress().getPort()),
				new CoreAuthenticationProvider("restart-token"), JsonMapper.builder().build(), Duration.ofSeconds(2));
	}

	private void handleCore(HttpExchange exchange) throws java.io.IOException {
		String path = exchange.getRequestURI().getPath();
		byte[] requestBody = exchange.getRequestBody().readAllBytes();
		if (path.equals("/internal/usage-reservations") && "POST".equals(exchange.getRequestMethod())) {
			coreCalls.add("reserve");
			var tree = JsonMapper.builder().build().readTree(new String(requestBody, StandardCharsets.UTF_8));
			reservationIds.add(tree.get("reservationId").asText());
			byte[] body = "{\"outcome\":\"AUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(201, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			return;
		}
		if (path.endsWith("/confirm")) {
			coreCalls.add("confirm");
			if (failConfirm.get()) {
				exchange.sendResponseHeaders(503, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return;
		}
		if (path.endsWith("/release")) {
			coreCalls.add("release");
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return;
		}
		if (path.endsWith("/refund")) {
			coreCalls.add("refund");
			exchange.sendResponseHeaders(refundStatus.get(), -1);
			exchange.close();
			return;
		}
		exchange.sendResponseHeaders(404, -1);
		exchange.close();
	}

	private static ExportLogsServiceRequest logsBatch(String body) {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.getDefaultInstance())
						.addScopeLogs(ScopeLogs.newBuilder().setScope(InstrumentationScope.getDefaultInstance())
								.addLogRecords(LogRecord.newBuilder().setSeverityNumberValue(9)
										.setBody(AnyValue.newBuilder().setStringValue(body)))))
				.build();
	}

	private static BatchStore<LogRecord> logsStore() {
		return (tenant, product, records) -> {
			List<String> ids = RecordIds.of(tenant, product, records);
			return new StoredBatch(Signal.LOGS, "org", "logs", "oo-restart", ids, 1L, 1L, NOW.toString());
		};
	}
}
