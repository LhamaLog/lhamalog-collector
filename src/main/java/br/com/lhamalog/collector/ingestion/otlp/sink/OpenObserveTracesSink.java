// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import io.opentelemetry.proto.trace.v1.Span;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces destination: OpenObserve stream {@code traces} (configurable), replacing the old Redis sink (T032).
 * Same confirmation rule as logs.
 */
public class OpenObserveTracesSink implements TracesSink {

	private final OpenObserveClient client;

	private final String stream;

	private final Clock clock;

	public OpenObserveTracesSink(OpenObserveClient client, String stream, Clock clock) {
		this.client = client;
		this.stream = stream;
		this.clock = clock;
	}

	@Override
	public void deliver(String tenantId, String productId, List<Span> acceptedSpans) {
		Instant receivedAt = clock.instant();
		long receivedMicros = Math.multiplyExact(receivedAt.getEpochSecond(), 1_000_000L) + receivedAt.getNano() / 1_000;
		List<Map<String, Object>> documents = new ArrayList<>(acceptedSpans.size());
		for (Span span : acceptedSpans) {
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("tenantId", tenantId);
			doc.put("productId", productId);
			doc.put("_timestamp",
					span.getStartTimeUnixNano() != 0 ? Long.divideUnsigned(span.getStartTimeUnixNano(), 1_000) : receivedMicros);
			doc.put("receivedAt", receivedAt.toString());
			doc.put("traceId", OtlpValues.hex(span.getTraceId()));
			doc.put("spanId", OtlpValues.hex(span.getSpanId()));
			if (!span.getParentSpanId().isEmpty()) {
				doc.put("parentSpanId", OtlpValues.hex(span.getParentSpanId()));
			}
			doc.put("name", span.getName());
			doc.put("startTimeUnixNano", Long.toUnsignedString(span.getStartTimeUnixNano()));
			doc.put("endTimeUnixNano", Long.toUnsignedString(span.getEndTimeUnixNano()));
			doc.put("attributes", OtlpValues.attributesJson(span.getAttributesList()));
			documents.add(doc);
		}
		client.ingest(stream, documents);
	}

}
