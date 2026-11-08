// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import br.com.lhamalog.collector.messaging.MetadataKeys;
import br.com.lhamalog.collector.messaging.OutboundMessage;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** LLT-182: the stream carries only a reference to the batch in OpenObserve. */
class TelemetryIngestedPublisherTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final MessagingProperties PROPERTIES = new MessagingProperties(key -> null);

	private static final LogIngestedPublisher LOGS = new LogIngestedPublisher(null, JSON, PROPERTIES, Clock.systemUTC());

	private static final TraceIngestedPublisher TRACES = new TraceIngestedPublisher(null, JSON, PROPERTIES,
			Clock.systemUTC());

	private static final Resource RESOURCE = Resource.newBuilder()
		.addAttributes(kv("service.name", "checkout"))
		.addAttributes(kv("tenantId", "spoofed-tenant"))
		.build();

	private static final ByteString TRACE = ByteString.copyFrom(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });

	private static final LogRecord INFO = log(9, "INFO", "order paid");

	private static KeyValue kv(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	private static LogRecord log(int severity, String text, String body) {
		return LogRecord.newBuilder()
			.setTimeUnixNano(1_700_000_000_000_000_000L)
			.setSeverityNumberValue(severity)
			.setSeverityText(text)
			.setBody(AnyValue.newBuilder().setStringValue(body))
			.addAttributes(kv("productId", "spoofed-product"))
			.setTraceId(TRACE)
			.build();
	}

	private static List<Scoped<LogRecord>> scoped(LogRecord... logs) {
		return Arrays.stream(logs).map(log -> new Scoped<>(RESOURCE, InstrumentationScope.getDefaultInstance(), log)).toList();
	}

	private static StoredBatch batch(Signal signal, List<String> ids) {
		return new StoredBatch(signal, "lhamalog", signal == Signal.LOGS ? "logs" : "traces", RecordIds.batchId(signal, ids),
				ids, 1_700_000_000_000_000L, 1_700_000_000_000_500L, "2026-11-01T12:00:00Z");
	}

	private static OutboundMessage<TelemetryIngestedBody> logs(String tenant, LogRecord... logs) {
		List<Scoped<LogRecord>> records = scoped(logs);
		return LOGS.message(tenant, "product-a", batch(Signal.LOGS, RecordIds.of(tenant, "product-a", records)), records,
				"corr-1");
	}

	@Test
	void logAndTraceSchemaVersionsAreOne() {
		assertThat(LogIngestedPublisher.SCHEMA_VERSION).isEqualTo(1);
		assertThat(TraceIngestedPublisher.SCHEMA_VERSION).isEqualTo(1);
	}

	@Test
	void metadataCarriesTokenIdsAndSingleTrace() {
		assertThat(logs("tenant-1", INFO).metadata()).containsEntry(MetadataKeys.TENANT_ID, "tenant-1")
			.containsEntry(MetadataKeys.PRODUCT_ID, "product-a")
			.containsEntry(MetadataKeys.CORRELATION_ID, "corr-1")
			.containsEntry(MetadataKeys.TRACE_ID, "00000000000000000000000000000001");
	}

	@Test
	void bodyIsAReferenceWithoutContent() {
		OutboundMessage<TelemetryIngestedBody> message = logs("tenant-1", INFO);
		TelemetryIngestedBody body = message.body();
		assertThat(body.signal()).isEqualTo("LOGS");
		assertThat(body.productId()).isEqualTo("product-a");
		assertThat(body.openobserve().organization()).isEqualTo("lhamalog");
		assertThat(body.openobserve().stream()).isEqualTo("logs");
		assertThat(body.openobserve().batchId()).isEqualTo(message.idempotencyId());
		assertThat(body.openobserve().recordId()).isNotBlank();
		assertThat(body.openobserve().minTimestampMicros()).isEqualTo(1_700_000_000_000_000L);
		assertThat(body.serviceNames()).containsExactly("checkout");
		assertThat(body.recordCount()).isEqualTo(1);
		assertThat(body.bytes()).isEqualTo(INFO.getSerializedSize());
		assertThat(body.receivedAt()).isEqualTo("2026-11-01T12:00:00Z");
		String json = JSON.writeValueAsString(body);
		assertThat(json).doesNotContain("order paid").doesNotContain("spoofed");
		Map<String, Object> map = JSON.convertValue(body, new TypeReference<Map<String, Object>>() { });
		assertThat(map).doesNotContainKeys("tenantId", "body", "attributes", "resource");
	}

	@Test
	void batchTakesHighestSeverityAndCountsEachLevel() {
		TelemetryIngestedBody body = logs("tenant-1", INFO, log(17, "ERROR", "a"), log(13, "WARN", "b"), log(0, "error", "c"))
			.body();
		assertThat(body.criticality()).isEqualTo("ERROR");
		assertThat(body.priority()).isEqualTo("HIGH");
		assertThat(body.maxSeverityNumber()).isEqualTo(17);
		assertThat(body.severityCounts()).containsEntry("ERROR", 2).containsEntry("WARN", 1).containsEntry("INFO", 1);
		assertThat(body.recordCount()).isEqualTo(4);
		assertThat(body.openobserve().recordId()).isNull();
	}

	@Test
	void batchAndRecordIdsAreDeterministicAndDistinctForDuplicates() {
		List<String> first = RecordIds.of("tenant-1", "product-a", scoped(INFO, INFO));
		List<String> retry = RecordIds.of("tenant-1", "product-a", scoped(INFO, INFO));
		assertThat(first).isEqualTo(retry).doesNotHaveDuplicates();
		assertThat(RecordIds.of("tenant-2", "product-a", scoped(INFO))).doesNotContainAnyElementsOf(first);
		assertThat(RecordIds.batchId(Signal.LOGS, first)).isEqualTo(RecordIds.batchId(Signal.LOGS, retry))
			.isNotEqualTo(RecordIds.batchId(Signal.TRACES, first));
	}

	@Test
	void spansHaveNoSeverity() {
		Span span = Span.newBuilder().setTraceId(TRACE).setName("GET /").build();
		List<Scoped<Span>> records = List.of(new Scoped<>(RESOURCE, InstrumentationScope.getDefaultInstance(), span));
		OutboundMessage<TelemetryIngestedBody> message = TRACES.message("tenant-1", "product-a",
				batch(Signal.TRACES, RecordIds.of("tenant-1", "product-a", records)), records, "corr-1");
		assertThat(message.body().signal()).isEqualTo("TRACES");
		assertThat(message.body().criticality()).isEqualTo("UNSPECIFIED");
		assertThat(message.body().priority()).isEqualTo("LOW");
		assertThat(TRACES.stream()).isEqualTo("lhamalog:stream:traces");
		assertThat(LOGS.stream()).isEqualTo("lhamalog:stream:logs");
	}

	@Test
	void criticalityComesOnlyFromSeverity() {
		assertThat(Criticality.of(21, "")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.FATAL.priority()).isEqualTo("CRITICAL");
		assertThat(Criticality.of(0, "Warning")).isEqualTo(Criticality.WARN);
		assertThat(Criticality.of(5, "")).isEqualTo(Criticality.DEBUG);
		assertThat(Criticality.of(0, "")).isEqualTo(Criticality.UNSPECIFIED);
	}

	@Test
	void criticalityOfSyslogAndOutOfRangeSeverities() {
		assertThat(Criticality.of(0, "CRIT")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(0, "critical")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(0, "EMERG")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(0, "Emergency")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(0, "ALERT")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(0, "NOTICE")).isEqualTo(Criticality.INFO);
		assertThat(Criticality.of(0, "TRACE")).isEqualTo(Criticality.TRACE);
		assertThat(Criticality.of(0, " warning ")).isEqualTo(Criticality.WARN);
		assertThat(Criticality.of(0, null)).isEqualTo(Criticality.UNSPECIFIED);
		assertThat(Criticality.of(25, "")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(-1, "ERROR")).isEqualTo(Criticality.ERROR);
		assertThat(Criticality.of(24, "")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(1, "")).isEqualTo(Criticality.TRACE);
	}

	@Test
	void severityNumberWinsOverConflictingText() {
		assertThat(Criticality.of(9, "ERROR")).isEqualTo(Criticality.INFO);
		assertThat(Criticality.of(17, "INFO")).isEqualTo(Criticality.ERROR);
		assertThat(Criticality.of(21, "DEBUG")).isEqualTo(Criticality.FATAL);
		assertThat(Criticality.of(17, "INFO").priority()).isEqualTo("HIGH");
	}

	@Test
	void serviceNamesAreDistinctSortedAndOnlyStrings() {
		Resource billing = Resource.newBuilder().addAttributes(kv("service.name", "billing")).build();
		Resource numeric = Resource.newBuilder()
			.addAttributes(KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder().setIntValue(42)))
			.build();
		InstrumentationScope scope = InstrumentationScope.getDefaultInstance();
		List<Scoped<LogRecord>> records = List.of(new Scoped<>(RESOURCE, scope, INFO), new Scoped<>(billing, scope, INFO),
				new Scoped<>(numeric, scope, INFO), new Scoped<>(RESOURCE, scope, log(13, "WARN", "x")));
		TelemetryIngestedBody body = LOGS
			.message("tenant-1", "product-a", batch(Signal.LOGS, RecordIds.of("tenant-1", "product-a", records)), records,
					"corr-1")
			.body();
		assertThat(body.serviceNames()).containsExactly("billing", "checkout");
		assertThat(body.recordCount()).isEqualTo(4);
	}

	@Test
	void tracesWithSeveralTraceIdsLeaveTraceIdOutOfMetadata() {
		ByteString other = ByteString.copyFrom(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2 });
		InstrumentationScope scope = InstrumentationScope.getDefaultInstance();
		List<Scoped<Span>> records = List.of(
				new Scoped<>(RESOURCE, scope, Span.newBuilder().setTraceId(TRACE).setName("a").build()),
				new Scoped<>(RESOURCE, scope, Span.newBuilder().setTraceId(other).setName("b").build()));
		OutboundMessage<TelemetryIngestedBody> message = TRACES.message("tenant-1", "product-a",
				batch(Signal.TRACES, RecordIds.of("tenant-1", "product-a", records)), records, "corr-1");
		assertThat(message.metadata()).doesNotContainKey(MetadataKeys.TRACE_ID)
			.containsEntry(MetadataKeys.TENANT_ID, "tenant-1");
		assertThat(message.body().openobserve().recordId()).isNull();
		assertThat(message.body().recordCount()).isEqualTo(2);
	}

}
