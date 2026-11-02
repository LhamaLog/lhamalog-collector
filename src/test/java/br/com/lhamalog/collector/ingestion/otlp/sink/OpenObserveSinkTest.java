// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Document shape and confirmation rule of the OpenObserve sinks against a fake endpoint (T017, T026, T032). */
class OpenObserveSinkTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);

	private final AtomicReference<String> lastPath = new AtomicReference<>();

	private final AtomicReference<String> lastBody = new AtomicReference<>();

	private final AtomicReference<String> lastAuth = new AtomicReference<>();

	private volatile int status = 200;

	private volatile String answer;

	private HttpServer server;

	private OpenObserveClient client;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			lastPath.set(exchange.getRequestURI().getPath());
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = (answer == null ? "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":1,\"failed\":0}]}" : answer)
				.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		client = new OpenObserveClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/",
				"lhamalog", "user", "secret");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static JsonNode first(String json) {
		return JsonMapper.builder().build().readTree(json).get(0);
	}

	@Test
	void logDocumentCarriesTokenIdentityAndFlatFields() {
		LogRecord log = LogRecord.newBuilder()
			.setTimeUnixNano(1_700_000_000_123_456_789L)
			.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
			.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
			.setSeverityNumberValue(13)
			.setBody(AnyValue.newBuilder().setStringValue("hello"))
			.addAttributes(KeyValue.newBuilder().setKey("productId").setValue(AnyValue.newBuilder().setStringValue("evil")))
			.build();
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("tenant-1", "product-a", List.of(Scoped.of(log)));
		assertThat(lastPath.get()).isEqualTo("/api/lhamalog/logs/_json");
		assertThat(lastAuth.get()).startsWith("Basic ");
		JsonNode doc = first(lastBody.get());
		assertThat(doc.get("tenantId").asString()).isEqualTo("tenant-1");
		assertThat(doc.get("productId").asString()).isEqualTo("product-a");
		assertThat(doc.get("_timestamp").asLong()).isEqualTo(1_700_000_000_123_456L);
		assertThat(doc.get("receivedAt").asString()).isEqualTo("2026-10-31T12:00:00Z");
		assertThat(doc.get("traceId").asString()).isEqualTo("5b8efff798038103d269b633813fc60c");
		assertThat(doc.get("spanId").asString()).isEqualTo("eee19b7ec3c1b174");
		assertThat(doc.get("severityNumber").asInt()).isEqualTo(13);
		assertThat(doc.get("body").asString()).isEqualTo("hello");
		assertThat(doc.get("attributes").asString()).isEqualTo("{\"productId\":\"evil\"}");
	}

	@Test
	void storeWritesBatchAndRecordIdsAndReturnsTheLocation() {
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":2,\"failed\":0}]}";
		LogRecord early = LogRecord.newBuilder().setTimeUnixNano(1_000_000_000L).build();
		LogRecord late = LogRecord.newBuilder().setTimeUnixNano(5_000_000_000L).build();
		StoredBatch batch = new OpenObserveLogsSink(client, "logs", CLOCK).store("t", "p",
				List.of(Scoped.of(early), Scoped.of(late)));
		JsonNode docs = JsonMapper.builder().build().readTree(lastBody.get());
		assertThat(docs.get(0).get("lhamalog_batch_id").asString()).isEqualTo(batch.batchId());
		assertThat(docs.get(1).get("lhamalog_batch_id").asString()).isEqualTo(batch.batchId());
		assertThat(batch.recordIds()).containsExactly(docs.get(0).get("lhamalog_record_id").asString(),
				docs.get(1).get("lhamalog_record_id").asString());
		assertThat(batch.singleRecordId()).isNull();
		assertThat(batch.signal()).isEqualTo(Signal.LOGS);
		assertThat(batch.organization()).isEqualTo("lhamalog");
		assertThat(batch.stream()).isEqualTo("logs");
		assertThat(batch.minTimestampMicros()).isEqualTo(1_000_000L);
		assertThat(batch.maxTimestampMicros()).isEqualTo(5_000_000L);
		assertThat(batch.receivedAt()).isEqualTo("2026-10-31T12:00:00Z");
	}

	@Test
	void singleSpanPointsStraightAtTheRecord() {
		Span span = Span.newBuilder()
			.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
			.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
			.setStartTimeUnixNano(2_000_000)
			.build();
		StoredBatch batch = new OpenObserveTracesSink(client, "traces", CLOCK).store("t", "p", List.of(Scoped.of(span)));
		JsonNode doc = first(lastBody.get());
		assertThat(batch.singleRecordId()).isEqualTo(doc.get("lhamalog_record_id").asString());
		assertThat(batch.signal()).isEqualTo(Signal.TRACES);
		assertThat(batch.minTimestampMicros()).isEqualTo(2_000L).isEqualTo(batch.maxTimestampMicros());
	}

	@Test
	void logWithoutTimeUsesAcceptInstant() {
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance())));
		JsonNode doc = first(lastBody.get());
		assertThat(doc.get("_timestamp").asLong()).isEqualTo(CLOCK.instant().toEpochMilli() * 1000);
		assertThat(doc.has("body")).isFalse();
		assertThat(doc.has("traceId")).isFalse();
	}

	@Test
	void spanDocumentHasHexIdsAndTimes() {
		Span span = Span.newBuilder()
			.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
			.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
			.setParentSpanId(ByteString.fromHex("0102030405060708"))
			.setName("GET /")
			.setStartTimeUnixNano(2_000_000)
			.setEndTimeUnixNano(3_000_000)
			.build();
		new OpenObserveTracesSink(client, "traces", CLOCK).deliver("tenant-1", "product-a", List.of(Scoped.of(span)));
		assertThat(lastPath.get()).isEqualTo("/api/lhamalog/traces/_json");
		JsonNode doc = first(lastBody.get());
		assertThat(doc.get("parentSpanId").asString()).isEqualTo("0102030405060708");
		assertThat(doc.get("name").asString()).isEqualTo("GET /");
		assertThat(doc.get("_timestamp").asLong()).isEqualTo(2_000);
		assertThat(doc.get("startTimeUnixNano").asString()).isEqualTo("2000000");
		assertThat(doc.get("endTimeUnixNano").asString()).isEqualTo("3000000");
	}

	@Test
	void non200FailedOrMissingConfirmationIsUnavailable() {
		var sink = new OpenObserveLogsSink(client, "logs", CLOCK);
		status = 500;
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
		status = 429;
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
		status = 401;
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
		status = 200;
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":0,\"failed\":0}]}";
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":1,\"failed\":0}]}";
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()), Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
	}

	@Test
	void unreachableOpenObserveIsUnavailable() {
		var dead = new OpenObserveClient(HttpClient.newHttpClient(), "http://127.0.0.1:1", "lhamalog", null, null);
		assertThatThrownBy(() -> new OpenObserveLogsSink(dead, "logs", CLOCK).deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOf(SinkUnavailableException.class);
	}

	// T040, FR-020: invalid data refused by OpenObserve is not "unavailable"

	@Test
	void dataErrorStatusesAreRejectionsOfTheWholeBatch() {
		var sink = new OpenObserveLogsSink(client, "logs", CLOCK);
		for (int code : new int[] { 400, 413, 415, 422 }) {
			status = code;
			answer = "{\"code\":" + code + ",\"message\":\"too old\"}";
			assertThatThrownBy(() -> sink.deliver("t", "p",
					List.of(Scoped.of(LogRecord.getDefaultInstance()), Scoped.of(LogRecord.getDefaultInstance()))))
				.isInstanceOfSatisfying(SinkRejectedException.class, ex -> {
					assertThat(ex.rejectedCount()).isEqualTo(2);
					assertThat(ex.acceptedCount()).isZero();
				});
		}
	}

	@Test
	void perRecordFailuresInA200AreRejectionsWithCounts() {
		var sink = new OpenObserveLogsSink(client, "logs", CLOCK);
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":1,\"failed\":2}]}";
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(Scoped.of(LogRecord.getDefaultInstance()),
				Scoped.of(LogRecord.getDefaultInstance()), Scoped.of(LogRecord.getDefaultInstance()))))
			.isInstanceOfSatisfying(SinkRejectedException.class, ex -> {
				assertThat(ex.rejectedCount()).isEqualTo(2);
				assertThat(ex.acceptedCount()).isEqualTo(1);
			});
	}

	// T041, T043, FR-021, FR-022

	private static final Resource RESOURCE = Resource.newBuilder()
		.addAttributes(KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder().setStringValue("checkout")))
		.addAttributes(KeyValue.newBuilder().setKey("host.name").setValue(AnyValue.newBuilder().setStringValue("vm-1")))
		.build();

	private static final InstrumentationScope SCOPE = InstrumentationScope.newBuilder().setName("io.lib").setVersion("2.1").build();

	@Test
	void logDocumentKeepsOtlpContext() {
		LogRecord log = LogRecord.newBuilder()
			.setTimeUnixNano(1_700_000_000_000_000_000L)
			.setObservedTimeUnixNano(1_700_000_000_500_000_000L)
			.setSeverityText("WARN")
			.setFlags(1)
			.build();
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("t", "p", List.of(new Scoped<>(RESOURCE, SCOPE, log)));
		JsonNode doc = first(lastBody.get());
		assertThat(doc.get("serviceName").asString()).isEqualTo("checkout");
		assertThat(doc.get("resourceAttributes").asString()).isEqualTo("{\"service.name\":\"checkout\",\"host.name\":\"vm-1\"}");
		assertThat(doc.get("scopeName").asString()).isEqualTo("io.lib");
		assertThat(doc.get("scopeVersion").asString()).isEqualTo("2.1");
		assertThat(doc.get("severityText").asString()).isEqualTo("WARN");
		assertThat(doc.get("observedTimeUnixNano").asString()).isEqualTo("1700000000500000000");
		assertThat(doc.get("flags").asLong()).isEqualTo(1);
		assertThat(doc.get("_timestamp").asLong()).isEqualTo(1_700_000_000_000_000L);
	}

	@Test
	void logWithoutTimeUsesObservedTimeBeforeAcceptInstant() {
		LogRecord log = LogRecord.newBuilder().setObservedTimeUnixNano(1_700_000_000_500_000_000L).build();
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("t", "p", List.of(Scoped.of(log)));
		assertThat(first(lastBody.get()).get("_timestamp").asLong()).isEqualTo(1_700_000_000_500_000L);
	}

	@Test
	void spanDocumentKeepsKindStatusEventsLinksAndContext() {
		Span span = Span.newBuilder()
			.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
			.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
			.setName("GET /")
			.setKind(Span.SpanKind.SPAN_KIND_SERVER)
			.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
			.setStartTimeUnixNano(2_000_000)
			.setEndTimeUnixNano(3_000_000)
			.addEvents(Span.Event.newBuilder()
				.setName("exception")
				.setTimeUnixNano(2_500_000)
				.addAttributes(KeyValue.newBuilder().setKey("exception.type").setValue(AnyValue.newBuilder().setStringValue("IOException"))))
			.addLinks(Span.Link.newBuilder()
				.setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
				.setSpanId(ByteString.fromHex("1112131415161718"))
				.setTraceState("k=v"))
			.build();
		new OpenObserveTracesSink(client, "traces", CLOCK).deliver("t", "p", List.of(new Scoped<>(RESOURCE, SCOPE, span)));
		JsonNode doc = first(lastBody.get());
		assertThat(doc.get("kind").asString()).isEqualTo("SPAN_KIND_SERVER");
		assertThat(doc.get("statusCode").asString()).isEqualTo("STATUS_CODE_ERROR");
		assertThat(doc.get("statusMessage").asString()).isEqualTo("boom");
		assertThat(doc.get("events").asString())
			.isEqualTo("[{\"timeUnixNano\":\"2500000\",\"name\":\"exception\",\"attributes\":{\"exception.type\":\"IOException\"}}]");
		assertThat(doc.get("links").asString()).isEqualTo(
				"[{\"traceId\":\"0102030405060708090a0b0c0d0e0f10\",\"spanId\":\"1112131415161718\",\"traceState\":\"k=v\",\"attributes\":{}}]");
		assertThat(doc.get("serviceName").asString()).isEqualTo("checkout");
		assertThat(doc.get("scopeName").asString()).isEqualTo("io.lib");
		assertThat(doc.get("scopeVersion").asString()).isEqualTo("2.1");
	}

}
