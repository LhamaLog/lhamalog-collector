// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import io.opentelemetry.proto.logs.v1.LogRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs destination: OpenObserve stream {@code logs} of organization {@code lhamalog} (T017, T026).
 *
 * <p>Each accepted log becomes one flat document with {@code tenantId} and {@code productId} of the token at
 * the top level (the batch cannot override them), {@code _timestamp} in microseconds from the log time (or
 * the accept instant), {@code receivedAt}, hex {@code traceId}/{@code spanId} when present,
 * {@code severityNumber}, {@code body} as text when present and {@code attributes} as JSON text.
 */
public class OpenObserveLogsSink implements LogsSink {

	private final OpenObserveClient client;

	private final String stream;

	private final Clock clock;

	public OpenObserveLogsSink(OpenObserveClient client, String stream, Clock clock) {
		this.client = client;
		this.stream = stream;
		this.clock = clock;
	}

	@Override
	public void deliver(String tenantId, String productId, List<LogRecord> acceptedRecords) {
		Instant receivedAt = clock.instant();
		long receivedMicros = Math.multiplyExact(receivedAt.getEpochSecond(), 1_000_000L) + receivedAt.getNano() / 1_000;
		List<Map<String, Object>> documents = new ArrayList<>(acceptedRecords.size());
		for (LogRecord log : acceptedRecords) {
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("tenantId", tenantId);
			doc.put("productId", productId);
			doc.put("_timestamp", log.getTimeUnixNano() != 0 ? Long.divideUnsigned(log.getTimeUnixNano(), 1_000) : receivedMicros);
			doc.put("receivedAt", receivedAt.toString());
			if (!log.getTraceId().isEmpty()) {
				doc.put("traceId", OtlpValues.hex(log.getTraceId()));
			}
			if (!log.getSpanId().isEmpty()) {
				doc.put("spanId", OtlpValues.hex(log.getSpanId()));
			}
			doc.put("severityNumber", log.getSeverityNumberValue());
			if (log.hasBody()) {
				doc.put("body", OtlpValues.text(log.getBody()));
			}
			doc.put("attributes", OtlpValues.attributesJson(log.getAttributesList()));
			documents.add(doc);
		}
		client.ingest(stream, documents);
	}

}
