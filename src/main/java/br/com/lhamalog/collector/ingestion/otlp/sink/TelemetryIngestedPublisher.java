// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.AbstractStreamPublisher;
import br.com.lhamalog.collector.messaging.MessageEnvelope;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import br.com.lhamalog.collector.messaging.MetadataKeys;
import br.com.lhamalog.collector.messaging.OutboundMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes one reference message per batch stored in OpenObserve (LLT-182). The idempotency id is the batch
 * id, so a client retry of the same batch publishes the same id. Priority and criticality come only from the
 * severity; the batch takes the highest one.
 */
public abstract class TelemetryIngestedPublisher<T extends MessageLite>
		extends AbstractStreamPublisher<TelemetryIngestedBody> {

	public static final String ORIGIN = "collector";

	protected TelemetryIngestedPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock) {
		super(redis, json, properties, clock);
	}

	@Override
	protected String origin() {
		return ORIGIN;
	}

	protected abstract int severityNumber(T record);

	protected abstract String severityText(T record);

	protected abstract ByteString traceId(T record);

	public List<MessageEnvelope> publishBatch(String tenantId, String productId, StoredBatch batch,
			List<Scoped<T>> records) {
		return publish(List.of(message(tenantId, productId, batch, records, UUID.randomUUID().toString())));
	}

	OutboundMessage<TelemetryIngestedBody> message(String tenantId, String productId, StoredBatch batch,
			List<Scoped<T>> records, String correlationId) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(MetadataKeys.TENANT_ID, tenantId);
		metadata.put(MetadataKeys.PRODUCT_ID, productId);
		metadata.put(MetadataKeys.CORRELATION_ID, correlationId);
		Set<String> traces = new LinkedHashSet<>();
		Set<String> services = new TreeSet<>();
		Map<Criticality, Integer> counts = new EnumMap<>(Criticality.class);
		Criticality highest = Criticality.UNSPECIFIED;
		int maxSeverity = 0;
		long bytes = 0;
		for (Scoped<T> scoped : records) {
			T record = scoped.record();
			if (!traceId(record).isEmpty()) {
				traces.add(OtlpValues.hex(traceId(record)));
			}
			for (KeyValue attribute : scoped.resource().getAttributesList()) {
				if (OtlpValues.SERVICE_NAME.equals(attribute.getKey())
						&& attribute.getValue().getValueCase() == AnyValue.ValueCase.STRING_VALUE) {
					services.add(attribute.getValue().getStringValue());
				}
			}
			Criticality criticality = Criticality.of(severityNumber(record), severityText(record));
			counts.merge(criticality, 1, Integer::sum);
			if (criticality.compareTo(highest) > 0) {
				highest = criticality;
			}
			maxSeverity = Math.max(maxSeverity, severityNumber(record));
			bytes += record.getSerializedSize();
		}
		if (traces.size() == 1) {
			metadata.put(MetadataKeys.TRACE_ID, traces.iterator().next());
		}
		Map<String, Integer> severityCounts = new LinkedHashMap<>();
		counts.forEach((criticality, count) -> severityCounts.put(criticality.name(), count));
		TelemetryIngestedBody body = new TelemetryIngestedBody(batch.signal().name(), productId,
				new TelemetryIngestedBody.OpenObserveRef(batch.organization(), batch.stream(), batch.batchId(),
						batch.singleRecordId(), batch.minTimestampMicros(), batch.maxTimestampMicros()),
				List.copyOf(services), highest.priority(), highest.name(), maxSeverity, severityCounts, records.size(),
				bytes, batch.receivedAt());
		return new OutboundMessage<>(batch.batchId(), body, metadata);
	}

}
