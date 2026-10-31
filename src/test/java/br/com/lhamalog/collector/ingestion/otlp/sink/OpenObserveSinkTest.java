// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
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
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("tenant-1", "product-a", List.of(log));
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
	void logWithoutTimeUsesAcceptInstant() {
		new OpenObserveLogsSink(client, "logs", CLOCK).deliver("t", "p", List.of(LogRecord.getDefaultInstance()));
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
		new OpenObserveTracesSink(client, "traces", CLOCK).deliver("tenant-1", "product-a", List.of(span));
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
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(LogRecord.getDefaultInstance())))
			.isInstanceOf(SinkUnavailableException.class);
		status = 200;
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":0,\"failed\":1}]}";
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(LogRecord.getDefaultInstance())))
			.isInstanceOf(SinkUnavailableException.class);
		answer = "{\"code\":200,\"status\":[{\"name\":\"x\",\"successful\":1,\"failed\":0}]}";
		assertThatThrownBy(() -> sink.deliver("t", "p", List.of(LogRecord.getDefaultInstance(), LogRecord.getDefaultInstance())))
			.isInstanceOf(SinkUnavailableException.class);
	}

	@Test
	void unreachableOpenObserveIsUnavailable() {
		var dead = new OpenObserveClient(HttpClient.newHttpClient(), "http://127.0.0.1:1", "lhamalog", null, null);
		assertThatThrownBy(() -> new OpenObserveLogsSink(dead, "logs", CLOCK).deliver("t", "p", List.of(LogRecord.getDefaultInstance())))
			.isInstanceOf(SinkUnavailableException.class);
	}

}
