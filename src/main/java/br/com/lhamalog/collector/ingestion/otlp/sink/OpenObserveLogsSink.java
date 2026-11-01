// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.LogRecordAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs destination: OpenObserve stream {@code logs} of organization {@code lhamalog} (T017, T026, T041, T043).
 *
 * <p>Each accepted log becomes one flat document with {@code tenantId} and {@code productId} of the token at
 * the top level (the batch cannot override them), {@code _timestamp} in microseconds from
 * {@code time_unix_nano}, else {@code observed_time_unix_nano}, else the accept instant; {@code receivedAt};
 * hex {@code traceId}/{@code spanId} when present; {@code severityNumber}; {@code severityText} when present;
 * {@code observedTimeUnixNano} when present; {@code flags}; {@code body} as text when present;
 * {@code attributes} as JSON text; and the resource and scope context ({@code serviceName},
 * {@code resourceAttributes}, {@code scopeName}, {@code scopeVersion}). OpenObserve stores field names in
 * lowercase; see {@code specs/001-otlp-receiver/data-model.md}.
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
	public void deliver(String tenantId, String productId, List<Scoped<LogRecord>> acceptedRecords) {
		Instant receivedAt = clock.instant();
		long receivedMicros = OtlpValues.micros(receivedAt);
		List<Map<String, Object>> documents = new ArrayList<>(acceptedRecords.size());
		for (Scoped<LogRecord> scoped : acceptedRecords) {
			LogRecord log = scoped.record();
			long time = LogRecordAssessment.effectiveTimeUnixNano(log);
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("tenantId", tenantId);
			doc.put("productId", productId);
			doc.put("_timestamp", time != 0 ? Long.divideUnsigned(time, 1_000) : receivedMicros);
			doc.put("receivedAt", receivedAt.toString());
			if (!log.getTraceId().isEmpty()) {
				doc.put("traceId", OtlpValues.hex(log.getTraceId()));
			}
			if (!log.getSpanId().isEmpty()) {
				doc.put("spanId", OtlpValues.hex(log.getSpanId()));
			}
			doc.put("severityNumber", log.getSeverityNumberValue());
			if (!log.getSeverityText().isEmpty()) {
				doc.put("severityText", log.getSeverityText());
			}
			if (log.getObservedTimeUnixNano() != 0) {
				doc.put("observedTimeUnixNano", Long.toUnsignedString(log.getObservedTimeUnixNano()));
			}
			doc.put("flags", Integer.toUnsignedLong(log.getFlags()));
			if (log.hasBody()) {
				doc.put("body", OtlpValues.text(log.getBody()));
			}
			doc.put("attributes", OtlpValues.attributesJson(log.getAttributesList()));
			OtlpValues.putContext(doc, scoped);
			documents.add(doc);
		}
		client.ingest(stream, documents);
	}

}
