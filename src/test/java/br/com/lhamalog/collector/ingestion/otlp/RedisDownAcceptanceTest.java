// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp;

import static br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.postProtobuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.lhamalog.collector.TestcontainersConfiguration;
import br.com.lhamalog.collector.ingestion.otlp.OtlpAcceptanceSupport.SeededProduct;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * LLT-182 end to end with Redis down: OpenObserve stores the batch but the queue does not confirm, so the
 * client gets a retryable answer, 503 with {@code Retry-After: 5} over HTTP and {@code UNAVAILABLE} over gRPC.
 * Own context (short Redis timeouts) and dirtied, since the test stops the Redis container of that context.
 */
@SpringBootTest(properties = { "spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=2s" })
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class RedisDownAcceptanceTest {

	@Autowired
	MongoTemplate mongo;

	@Autowired
	OtlpHttpServer httpServer;

	@Autowired
	OtlpGrpcServer grpcServer;

	@Autowired
	@Qualifier("redisContainer")
	GenericContainer<?> redisContainer;

	private static ExportLogsServiceRequest logs() {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.addScopeLogs(ScopeLogs.newBuilder()
					.addLogRecords(LogRecord.newBuilder()
						.setTimeUnixNano(OtlpAcceptanceSupport.nowNanos())
						.setSeverityNumberValue(17)
						.setBody(AnyValue.newBuilder().setStringValue("redis-down-" + UUID.randomUUID())))))
			.build();
	}

	private static ExportTraceServiceRequest traces() {
		return ExportTraceServiceRequest.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.addScopeSpans(ScopeSpans.newBuilder()
					.addSpans(Span.newBuilder()
						.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
						.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
						.setName("redis-down-" + UUID.randomUUID())
						.setStartTimeUnixNano(OtlpAcceptanceSupport.nowNanos()))))
			.build();
	}

	@Test
	void queueDownIsRetryableOnBothTransportsAndSignals() throws Exception {
		SeededProduct product = OtlpAcceptanceSupport.seedProduct(mongo);
		redisContainer.stop();

		for (String path : new String[] { "/v1/logs", "/v1/traces" }) {
			byte[] body = path.equals("/v1/logs") ? logs().toByteArray() : traces().toByteArray();
			HttpResponse<byte[]> response = postProtobuf(httpServer.getPort(), path, product.token(), body);
			assertThat(response.statusCode()).as(path).isEqualTo(503);
			assertThat(response.headers().firstValue("Retry-After")).as(path).contains("5");
		}

		ManagedChannel channel = OtlpAcceptanceSupport.channel(grpcServer.getPort());
		try {
			StatusRuntimeException logsError = catchThrowableOfType(StatusRuntimeException.class,
					() -> LogsServiceGrpc.newBlockingStub(channel)
						.withInterceptors(OtlpAcceptanceSupport.bearer(product.token()))
						.withDeadlineAfter(30, TimeUnit.SECONDS)
						.export(logs()));
			assertThat(logsError.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
			StatusRuntimeException tracesError = catchThrowableOfType(StatusRuntimeException.class,
					() -> TraceServiceGrpc.newBlockingStub(channel)
						.withInterceptors(OtlpAcceptanceSupport.bearer(product.token()))
						.withDeadlineAfter(30, TimeUnit.SECONDS)
						.export(traces()));
			assertThat(tracesError.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
		}
		finally {
			channel.shutdownNow();
		}
	}

}
