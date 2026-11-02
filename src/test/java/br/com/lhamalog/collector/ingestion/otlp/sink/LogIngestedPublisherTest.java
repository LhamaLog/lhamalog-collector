// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MetadataKeys;
import br.com.lhamalog.collector.messaging.OutboundMessage;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LogIngestedPublisherTest {

	private static final Resource RESOURCE = Resource.newBuilder()
		.addAttributes(kv("service.name", "checkout"))
		.addAttributes(kv("deployment.environment", "prod"))
		.addAttributes(kv("tenantId", "spoofed-tenant"))
		.build();

	private static final InstrumentationScope SCOPE = InstrumentationScope.newBuilder()
		.setName("app.logger")
		.setVersion("1.2.3")
		.build();

	private static final LogRecord LOG = LogRecord.newBuilder()
		.setTimeUnixNano(1_700_000_000_000_000_000L)
		.setSeverityNumberValue(9)
		.setSeverityText("INFO")
		.setBody(AnyValue.newBuilder().setStringValue("order paid"))
		.addAttributes(kv("order.id", "o-42"))
		.addAttributes(kv("productId", "spoofed-product"))
		.setTraceId(ByteString.copyFrom(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 }))
		.build();

	private static KeyValue kv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private static List<OutboundMessage<LogIngestedBody>> messages(String tenant, LogRecord... logs) {
		return LogIngestedPublisher.messages(tenant, "product-a",
				java.util.Arrays.stream(logs).map(log -> new Scoped<>(RESOURCE, SCOPE, log)).toList(), "corr-1",
				"2026-11-01T12:00:00Z");
	}

	@Test
	void metadataCarriesTokenIdsAndTrace() {
		OutboundMessage<LogIngestedBody> message = messages("tenant-1", LOG).getFirst();
		assertThat(message.metadata()).containsEntry(MetadataKeys.TENANT_ID, "tenant-1")
			.containsEntry(MetadataKeys.PRODUCT_ID, "product-a")
			.containsEntry(MetadataKeys.CORRELATION_ID, "corr-1")
			.containsEntry(MetadataKeys.TRACE_ID, "00000000000000000000000000000001");
	}

	@Test
	void bodyPreservesResourceScopeAndLogAttributes() {
		LogIngestedBody body = messages("tenant-1", LOG).getFirst().body();
		assertThat(body.resource().serviceName()).isEqualTo("checkout");
		assertThat(body.resource().attributes()).containsEntry("deployment.environment", "prod")
			.containsEntry("tenantId", "spoofed-tenant");
		assertThat(body.scope().name()).isEqualTo("app.logger");
		assertThat(body.scope().version()).isEqualTo("1.2.3");
		assertThat(body.attributes()).containsEntry("order.id", "o-42").containsEntry("productId", "spoofed-product");
		assertThat(body.body()).isEqualTo("order paid");
		assertThat(body.severityText()).isEqualTo("INFO");
		assertThat(body.timeUnixNano()).isEqualTo("1700000000000000000");
		assertThat(body.observedTimeUnixNano()).isNull();
	}

	@Test
	void serializedBodyHasNoTopLevelTenantOrProduct() {
		Map<String, Object> json = JsonMapper.builder().build().convertValue(messages("tenant-1", LOG).getFirst().body(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
		assertThat(json).doesNotContainKeys("tenantId", "productId");
	}

	@Test
	void idempotencyIdIsDeterministicAndDistinctForDuplicates() {
		List<OutboundMessage<LogIngestedBody>> first = messages("tenant-1", LOG, LOG);
		List<OutboundMessage<LogIngestedBody>> retry = messages("tenant-1", LOG, LOG);
		assertThat(first.get(0).idempotencyId()).isEqualTo(retry.get(0).idempotencyId());
		assertThat(first.get(1).idempotencyId()).isEqualTo(retry.get(1).idempotencyId());
		assertThat(first.get(0).idempotencyId()).isNotEqualTo(first.get(1).idempotencyId());
		assertThat(messages("tenant-2", LOG).getFirst().idempotencyId()).isNotEqualTo(first.get(0).idempotencyId());
	}

	@Test
	void logWithoutTraceHasNoTraceMetadata() {
		LogRecord plain = LogRecord.newBuilder().setSeverityNumberValue(9).build();
		assertThat(messages("tenant-1", plain).getFirst().metadata()).doesNotContainKey(MetadataKeys.TRACE_ID);
	}

}
