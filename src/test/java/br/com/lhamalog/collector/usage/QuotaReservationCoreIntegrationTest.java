package br.com.lhamalog.collector.usage;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingTracesSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.RecordIds;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.quota.CanonicalQuotaGate;
import br.com.lhamalog.collector.quota.HttpQuotaReservationClient;
import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.MongoUsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
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
 * T025: Collector–Core reservation happy path for logs and traces with a stubbed Core that
 * represents an active cycle and sufficient balance.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuotaReservationCoreIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String TOKEN = "integration-service-token";

	@Container
	static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static MongoClient client;
	private static MongoUsageOutboxRepository outbox;

	private HttpServer core;
	private final List<String> coreCalls = new CopyOnWriteArrayList<>();
	private final List<Long> reservedBytes = new CopyOnWriteArrayList<>();
	private final List<String> reservationIds = new CopyOnWriteArrayList<>();
	private final List<String> deliveries = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void startMongo() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		outbox = new MongoUsageOutboxRepository(
				new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "quota_core_integration")));
	}

	@AfterAll
	static void stopMongo() {
		client.close();
	}

	@BeforeEach
	void startCore() throws Exception {
		coreCalls.clear();
		reservedBytes.clear();
		reservationIds.clear();
		deliveries.clear();
		core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		core.createContext("/internal/usage-reservations", this::handleCore);
		core.start();
	}

	@AfterEach
	void stopCore() {
		core.stop(0);
	}

	@Test
	void logsAndTracesReserveConfirmDeliverWithCanonicalBytesAndNoSecondCount() {
		HttpQuotaReservationClient client = reservationClient();
		CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> logsGate = new CanonicalQuotaGate<>(client);
		CanonicalQuotaGate<ExportTraceServiceRequest, Scoped<Span>> tracesGate = new CanonicalQuotaGate<>(client);
		UsageFactRecorder recorder = new UsageFactRecorder(outbox, CLOCK);
		UsageFactFactory facts = new UsageFactFactory(CLOCK);

		ExportLogsServiceRequest logsRequest = logsBatch();
		ExportTraceServiceRequest tracesRequest = tracesBatch();
		PublishingLogsSink logsSink = new PublishingLogsSink(logsStore(), (tenant, product, batch, records) -> {
		}, facts, recorder, (tenant, product, batch, records, key) -> {
			deliveries.add("logs");
			return "DONE:logs";
		});
		PublishingTracesSink tracesSink = new PublishingTracesSink(tracesStore(), (tenant, product, batch, records) -> {
		}, facts, recorder, (tenant, product, batch, records, key) -> {
			deliveries.add("traces");
			return "DONE:traces";
		});

		ExportLogsService logs = new ExportLogsService(token -> new TokenValidationResult.Valid("tenant", "product"),
				logsSink, br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow.DEFAULT, CLOCK, logsGate);
		ExportTracesService traces = new ExportTracesService(token -> new TokenValidationResult.Valid("tenant", "product"),
				tracesSink, br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow.DEFAULT, CLOCK, tracesGate);

		assertThat(logs.export("bearer", () -> logsRequest)).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(traces.export("bearer", () -> tracesRequest)).isEqualTo(new IngestionDecision.Accepted(1));

		assertThat(coreCalls).containsExactly("reserve", "confirm", "reserve", "confirm");
		assertThat(deliveries).containsExactly("logs", "traces");
		assertThat(reservationIds).hasSize(2).doesNotHaveDuplicates();
		assertThat(reservedBytes).hasSize(2).allMatch(bytes -> bytes > 0);

		UsageFact logsFact = facts.logs("tenant", "product", logsRequest,
				List.of(new Scoped<>(Resource.getDefaultInstance(), InstrumentationScope.getDefaultInstance(),
						LogRecord.newBuilder().setSeverityNumberValue(9).setBody(
								io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("quota-logs").build())
								.build())));
		UsageFact tracesFact = facts.traces("tenant", "product", tracesRequest,
				List.of(new Scoped<>(Resource.getDefaultInstance(), InstrumentationScope.getDefaultInstance(), span())));

		assertThat(reservedBytes.get(0)).isEqualTo(logsFact.acceptedBytes());
		assertThat(reservedBytes.get(1)).isEqualTo(tracesFact.acceptedBytes());

		assertThat(logs.export("bearer", () -> logsRequest)).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(traces.export("bearer", () -> tracesRequest)).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(coreCalls).containsExactly("reserve", "confirm", "reserve", "confirm", "reserve", "confirm",
				"reserve", "confirm");
		assertThat(reservationIds.get(0)).isEqualTo(reservationIds.get(2));
		assertThat(reservationIds.get(1)).isEqualTo(reservationIds.get(3));

		UsageOutboxItem logsItem = outbox.findById(logsFact.usageFactId()).orElseThrow();
		UsageOutboxItem tracesItem = outbox.findById(tracesFact.usageFactId()).orElseThrow();
		assertThat(logsItem.fact().acceptedBytes()).isEqualTo(reservedBytes.get(0));
		assertThat(tracesItem.fact().acceptedBytes()).isEqualTo(reservedBytes.get(1));
		assertThat(logsItem.fact().domain()).isEqualTo(UsageDomain.LOGS);
		assertThat(tracesItem.fact().domain()).isEqualTo(UsageDomain.TRACES);
		assertThat(logsItem.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(tracesItem.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(outbox.findById(logsFact.usageFactId()).orElseThrow().fact().acceptedRecordCount()).isEqualTo(1);
		assertThat(outbox.findById(tracesFact.usageFactId()).orElseThrow().fact().acceptedRecordCount()).isEqualTo(1);
	}

	private HttpQuotaReservationClient reservationClient() {
		return new HttpQuotaReservationClient(HttpClient.newHttpClient(),
				URI.create("http://127.0.0.1:" + core.getAddress().getPort()), new CoreAuthenticationProvider(TOKEN),
				JsonMapper.builder().build(), Duration.ofSeconds(2));
	}

	private void handleCore(HttpExchange exchange) throws java.io.IOException {
		String path = exchange.getRequestURI().getPath();
		byte[] requestBody = exchange.getRequestBody().readAllBytes();
		if (path.equals("/internal/usage-reservations") && "POST".equals(exchange.getRequestMethod())) {
			coreCalls.add("reserve");
			var tree = JsonMapper.builder().build().readTree(new String(requestBody, StandardCharsets.UTF_8));
			reservationIds.add(tree.get("reservationId").asText());
			reservedBytes.add(tree.get("requestedBytes").asLong());
			byte[] body = "{\"outcome\":\"AUTHORIZED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(201, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			return;
		}
		if (path.endsWith("/confirm")) {
			coreCalls.add("confirm");
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
		exchange.sendResponseHeaders(404, -1);
		exchange.close();
	}

	private static ExportLogsServiceRequest logsBatch() {
		return ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.getDefaultInstance())
						.addScopeLogs(ScopeLogs.newBuilder().setScope(InstrumentationScope.getDefaultInstance())
								.addLogRecords(LogRecord.newBuilder().setSeverityNumberValue(9)
										.setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
												.setStringValue("quota-logs")))))
				.build();
	}

	private static ExportTraceServiceRequest tracesBatch() {
		return ExportTraceServiceRequest.newBuilder()
				.addResourceSpans(ResourceSpans.newBuilder().setResource(Resource.getDefaultInstance())
						.addScopeSpans(ScopeSpans.newBuilder().setScope(InstrumentationScope.getDefaultInstance())
								.addSpans(span())))
				.build();
	}

	private static Span span() {
		byte[] traceId = new byte[16];
		byte[] spanId = new byte[8];
		traceId[0] = 7;
		spanId[0] = 9;
		return Span.newBuilder().setTraceId(com.google.protobuf.ByteString.copyFrom(traceId))
				.setSpanId(com.google.protobuf.ByteString.copyFrom(spanId)).setName("quota-traces").build();
	}

	private static BatchStore<LogRecord> logsStore() {
		return (tenant, product, records) -> {
			List<String> ids = RecordIds.of(tenant, product, records);
			return new StoredBatch(Signal.LOGS, "org", "logs", "oo-logs", ids, 1L, 1L, NOW.toString());
		};
	}

	private static BatchStore<Span> tracesStore() {
		return (tenant, product, records) -> {
			List<String> ids = RecordIds.of(tenant, product, records);
			return new StoredBatch(Signal.TRACES, "org", "traces", "oo-traces", ids, 1L, 1L, NOW.toString());
		};
	}
}
