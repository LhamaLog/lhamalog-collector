// Author: LhamaLog Desenvolvimento (IA) - LLT-181, LLT-182
package br.com.lhamalog.collector.ingestion.otlp;

import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.awaitDocuments;
import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.postProtobuf;
import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.TestcontainersConfiguration;
import br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.SeededProduct;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * User story 1 end to end: MongoDB, OpenObserve and Redis in Testcontainers, receiver on free ports (T015).
 * LLT-182: every accepted log also lands in {@code lhamalog:stream:logs} with the token identity in the
 * envelope metadata, and refused or invalid logs never reach the stream.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExportLogsAcceptanceTest {

	@Autowired
	MongoTemplate mongo;

	@Autowired
	OtlpHttpServer httpServer;

	@Autowired
	OtlpGrpcServer grpcServer;

	@Autowired
	Environment env;

	@Autowired
	StringRedisTemplate redis;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Stream entries whose log body is the marker, parsed as {metadata, body}. */
	private List<JsonNode[]> streamEntries(String marker) {
		List<MapRecord<String, Object, Object>> entries = redis.opsForStream().range("lhamalog:stream:logs", Range.unbounded());
		return entries.stream()
			.map(entry -> new JsonNode[] { JSON.readTree((String) entry.getValue().get("metadata")),
					JSON.readTree((String) entry.getValue().get("body")) })
			.filter(pair -> marker.equals(pair[1].path("body").asString(null)))
			.toList();
	}

	private static AnyValue string(String value) {
		return AnyValue.newBuilder().setStringValue(value).build();
	}

	/** One log whose resource and attributes try to name another product and tenant. */
	private static ExportLogsServiceRequest spoofingBatch(String marker) {
		KeyValue fakeProduct = KeyValue.newBuilder().setKey("productId").setValue(string("someone-else")).build();
		KeyValue fakeTenant = KeyValue.newBuilder().setKey("tenantId").setValue(string("other-tenant")).build();
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.setResource(Resource.newBuilder()
					.addAttributes(KeyValue.newBuilder().setKey("service.name").setValue(string("checkout")))
					.addAttributes(fakeProduct)
					.addAttributes(fakeTenant))
				.addScopeLogs(ScopeLogs.newBuilder()
					.addLogRecords(LogRecord.newBuilder()
						.setTimeUnixNano(OtlpAcceptanceSupport.nowNanos())
						.setSeverityNumberValue(9)
						.setBody(string(marker))
						.addAttributes(fakeProduct)
						.addAttributes(fakeTenant))))
			.build();
	}

	private void assertStoredWithTokenIdentity(String marker, SeededProduct product) throws Exception {
		List<JsonNode> documents = awaitDocuments(env, "logs", "body", marker);
		assertThat(documents).hasSize(1);
		assertThat(text(documents.getFirst(), "productId")).isEqualTo(product.productId());
		assertThat(text(documents.getFirst(), "tenantId")).isEqualTo(product.tenantId());
		assertThat(text(documents.getFirst(), "severityNumber")).isEqualTo("9");

		List<JsonNode[]> published = streamEntries(marker);
		assertThat(published).hasSize(1);
		JsonNode metadata = published.getFirst()[0];
		JsonNode body = published.getFirst()[1];
		assertThat(metadata.path("tenantId").asString()).isEqualTo(product.tenantId());
		assertThat(metadata.path("productId").asString()).isEqualTo(product.productId());
		assertThat(body.has("tenantId")).isFalse();
		assertThat(body.path("resource").path("serviceName").asString()).isEqualTo("checkout");
		assertThat(body.path("resource").path("attributes").path("tenantId").asString()).isEqualTo("other-tenant");
		assertThat(body.path("attributes").path("productId").asString()).isEqualTo("someone-else");
	}

	@Test
	void httpBatchIsConfirmedAndStoredWithTheProductOfTheToken() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		String marker = "http-log-" + UUID.randomUUID();
		HttpResponse<byte[]> response = postProtobuf(httpServer.getPort(), "/v1/logs", product.token(),
				spoofingBatch(marker).toByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(ExportLogsServiceResponse.parseFrom(response.body()).hasPartialSuccess()).isFalse();
		assertStoredWithTokenIdentity(marker, product);
	}

	@Test
	void grpcBatchIsConfirmedAndStoredWithTheProductOfTheToken() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		String marker = "grpc-log-" + UUID.randomUUID();
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			ExportLogsServiceResponse response = LogsServiceGrpc.newBlockingStub(channel)
				.withInterceptors(OtlpAcceptanceSupport.bearer(product.token()))
				.withDeadlineAfter(30, TimeUnit.SECONDS)
				.export(spoofingBatch(marker));
			assertThat(response.hasPartialSuccess()).isFalse();
		}
		finally {
			channel.shutdownNow();
		}
		assertStoredWithTokenIdentity(marker, product);
	}

	@Test
	void emptyBatchSucceedsOnBothTransports() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		ExportLogsServiceRequest empty = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.getDefaultInstance()).build();
		assertThat(postProtobuf(httpServer.getPort(), "/v1/logs", product.token(), empty.toByteArray()).statusCode()).isEqualTo(200);
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			assertThat(LogsServiceGrpc.newBlockingStub(channel)
				.withInterceptors(OtlpAcceptanceSupport.bearer(product.token()))
				.withDeadlineAfter(30, TimeUnit.SECONDS)
				.export(empty)
				.hasPartialSuccess()).isFalse();
		}
		finally {
			channel.shutdownNow();
		}
	}

	@Test
	void revokedOrUnknownTokenIsRefused() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		mongo.getCollection("product_tokens")
			.updateOne(new org.bson.Document("productId", product.productId()),
					new org.bson.Document("$set", new org.bson.Document("status", "REVOKED").append("revokedAt", new java.util.Date())));
		String marker = "refused-" + UUID.randomUUID();
		byte[] body = spoofingBatch(marker).toByteArray();
		assertThat(postProtobuf(httpServer.getPort(), "/v1/logs", product.token(), body).statusCode()).isEqualTo(401);
		assertThat(postProtobuf(httpServer.getPort(), "/v1/logs", "not-a-token", body).statusCode()).isEqualTo(401);
		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class,
					() -> LogsServiceGrpc.newBlockingStub(channel)
						.withInterceptors(OtlpAcceptanceSupport.bearer("not-a-token"))
						.withDeadlineAfter(30, TimeUnit.SECONDS)
						.export(spoofingBatch("x")));
			assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
		}
		finally {
			channel.shutdownNow();
		}
		assertThat(streamEntries(marker)).isEmpty();
	}

	@Test
	void invalidBatchNeverReachesTheStream() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		String marker = "invalid-" + UUID.randomUUID();
		ExportLogsServiceRequest invalid = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.addScopeLogs(ScopeLogs.newBuilder()
					.addLogRecords(LogRecord.newBuilder()
						.setTimeUnixNano(OtlpAcceptanceSupport.nowNanos())
						.setTraceId(com.google.protobuf.ByteString.copyFromUtf8("short"))
						.setBody(string(marker)))))
			.build();
		assertThat(postProtobuf(httpServer.getPort(), "/v1/logs", product.token(), invalid.toByteArray()).statusCode())
			.isEqualTo(400);
		assertThat(streamEntries(marker)).isEmpty();
	}

}
