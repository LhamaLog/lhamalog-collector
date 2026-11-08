// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp;

import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.awaitDocuments;
import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.postProtobuf;
import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.TestcontainersConfiguration;
import br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.SeededProduct;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveClient;
import br.com.lhamalog.collector.ingestion.otlp.sink.OpenObserveTracesSink;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;

/** User story 3 end to end (T030). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExportTracesAcceptanceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

	@Autowired
	MongoTemplate mongo;

	@Autowired
	OtlpHttpServer httpServer;

	@Autowired
	OtlpGrpcServer grpcServer;

	@Autowired
	TokenValidator tokens;

	@Autowired
	Environment env;

	@Autowired
	org.springframework.data.redis.core.StringRedisTemplate redis;

	private static ByteString randomId(int size) {
		byte[] bytes = new byte[size];
		RANDOM.nextBytes(bytes);
		bytes[0] |= 1;
		return ByteString.copyFrom(bytes);
	}

	private static ExportTraceServiceRequest batch(Span span) {
		return ExportTraceServiceRequest.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addSpans(span)))
			.build();
	}

	private static Span span(String name) {
		long start = OtlpAcceptanceSupport.nowNanos();
		return Span.newBuilder()
			.setTraceId(randomId(16))
			.setSpanId(randomId(8))
			.setParentSpanId(randomId(8))
			.setName(name)
			.setStartTimeUnixNano(start)
			.setEndTimeUnixNano(start + 1_000_000)
			.build();
	}

	private TraceServiceGrpc.TraceServiceBlockingStub stub(ManagedChannel channel, String token) {
		return TraceServiceGrpc.newBlockingStub(channel)
			.withInterceptors(OtlpAcceptanceSupport.bearer(token))
			.withDeadlineAfter(30, TimeUnit.SECONDS);
	}

	@Test
	void validSpansAreStoredInTracesStreamWithTheProductOfTheToken() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		String httpName = "http-span-" + UUID.randomUUID();
		HttpResponse<byte[]> response = postProtobuf(httpServer.getPort(), "/v1/traces", product.token(),
				batch(span(httpName)).toByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(ExportTraceServiceResponse.parseFrom(response.body()).hasPartialSuccess()).isFalse();

		String grpcName = "grpc-span-" + UUID.randomUUID();
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			assertThat(stub(channel, product.token()).export(batch(span(grpcName))).hasPartialSuccess()).isFalse();
		}
		finally {
			channel.shutdownNow();
		}
		for (String name : List.of(httpName, grpcName)) {
			List<JsonNode> documents = awaitDocuments(env, "traces", "name", name);
			assertThat(documents).hasSize(1);
			assertThat(text(documents.getFirst(), "productId")).isEqualTo(product.productId());
			assertThat(text(documents.getFirst(), "tenantId")).isEqualTo(product.tenantId());
			assertThat(text(documents.getFirst(), "parentSpanId")).hasSize(16);
			String batchId = text(documents.getFirst(), "lhamalog_batch_id");
			List<tools.jackson.databind.JsonNode> references = redis.opsForStream()
				.range("lhamalog:stream:traces", org.springframework.data.domain.Range.unbounded())
				.stream()
				.map(entry -> JSON.readTree((String) entry.getValue().get("body")))
				.filter(body -> batchId.equals(body.path("openobserve").path("batchId").asString(null)))
				.toList();
			assertThat(references).hasSize(1);
			assertThat(references.getFirst().path("signal").asString()).isEqualTo("TRACES");
			assertThat(references.getFirst().path("openobserve").path("recordId").asString())
				.isEqualTo(text(documents.getFirst(), "lhamalog_record_id"));
			assertThat(references.getFirst().toString()).doesNotContain(name);
		}
	}

	@Test
	void invalidTokenAndInvalidSpanRepeatTheFailureClasses() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		byte[] invalidSpan = batch(span("bad").toBuilder().setTraceId(ByteString.copyFrom(new byte[16])).build()).toByteArray();
		assertThat(postProtobuf(httpServer.getPort(), "/v1/traces", "wrong", batch(span("x")).toByteArray()).statusCode()).isEqualTo(401);
		assertThat(postProtobuf(httpServer.getPort(), "/v1/traces", product.token(), invalidSpan).statusCode()).isEqualTo(400);
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			assertThat(catchThrowableOfType(StatusRuntimeException.class, () -> stub(channel, "wrong").export(batch(span("x"))))
				.getStatus()
				.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
			assertThat(catchThrowableOfType(StatusRuntimeException.class,
					() -> stub(channel, product.token()).export(ExportTraceServiceRequest.parseFrom(invalidSpan)))
				.getStatus()
				.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		}
		finally {
			channel.shutdownNow();
		}
	}

	@Test
	void openObserveDownIsUnavailableOnBothTransports() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		OpenObserveClient deadClient = new OpenObserveClient(HttpClient.newHttpClient(), "http://127.0.0.1:1", "lhamalog", "u", "p");
		ExportTracesService traces = new ExportTracesService(tokens, new OpenObserveTracesSink(deadClient, "traces", Clock.systemUTC()));
		LogsSink unusedLogs = (tenant, productId, records) -> {
		};
		ExportLogsService logs = new ExportLogsService(tokens, unusedLogs);
		OtlpHttpServer http = new OtlpHttpServer("127.0.0.1", 0, 1 << 20, logs, traces);
		OtlpGrpcServer grpc = new OtlpGrpcServer("127.0.0.1", 0, 1 << 20, logs, traces);
		http.start();
		grpc.start();
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpc.getPort());
		try {
			HttpResponse<byte[]> response = postProtobuf(http.getPort(), "/v1/traces", product.token(), batch(span("down")).toByteArray());
			assertThat(response.statusCode()).isEqualTo(503);
			assertThat(response.headers().firstValue("Retry-After")).isPresent();
			assertThat(catchThrowableOfType(StatusRuntimeException.class, () -> stub(channel, product.token()).export(batch(span("down"))))
				.getStatus()
				.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
		}
		finally {
			channel.shutdownNow();
			http.stop();
			grpc.stop();
		}
	}

	@Test
	void resentBatchKeepsBatchIdIdempotencyIdAndRecordId() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		String name = "resend-span-" + UUID.randomUUID();
		long start = OtlpAcceptanceSupport.nowNanos();
		Span fixed = Span.newBuilder()
			.setTraceId(ByteString.copyFrom(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }))
			.setSpanId(ByteString.copyFrom(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }))
			.setParentSpanId(ByteString.copyFrom(new byte[] { 9, 10, 11, 12, 13, 14, 15, 16 }))
			.setName(name)
			.setStartTimeUnixNano(start)
			.setEndTimeUnixNano(start + 1_000_000L)
			.build();
		byte[] body = batch(fixed).toByteArray();
		assertThat(postProtobuf(httpServer.getPort(), "/v1/traces", product.token(), body).statusCode()).isEqualTo(200);
		assertThat(postProtobuf(httpServer.getPort(), "/v1/traces", product.token(), body).statusCode()).isEqualTo(200);

		long end = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
		List<JsonNode> documents = awaitDocuments(env, "traces", "name", name);
		while (documents.size() < 2 && System.nanoTime() < end) {
			Thread.sleep(1000);
			documents = OtlpAcceptanceSupport.search(env, "traces", "name", name);
		}
		assertThat(documents).hasSize(2);
		assertThat(text(documents.get(0), "lhamalog_record_id")).isEqualTo(text(documents.get(1), "lhamalog_record_id"));
		String batchId = text(documents.get(0), "lhamalog_batch_id");
		String recordId = text(documents.get(0), "lhamalog_record_id");
		assertThat(text(documents.get(1), "lhamalog_batch_id")).isEqualTo(batchId);

		List<MapRecord<String, Object, Object>> entries = redis.opsForStream()
			.range("lhamalog:stream:traces", Range.unbounded())
			.stream()
			.filter(entry -> batchId.equals(JSON.readTree((String) entry.getValue().get("body"))
				.path("openobserve")
				.path("batchId")
				.asString(null)))
			.toList();
		assertThat(entries).hasSize(2);
		assertThat(entries).allSatisfy(entry -> assertThat(entry.getValue().get("idempotencyId")).isEqualTo(batchId));
		assertThat(entries).allSatisfy(entry -> assertThat(JSON.readTree((String) entry.getValue().get("body"))
			.path("openobserve")
			.path("recordId")
			.asString()).isEqualTo(recordId));
	}

}
