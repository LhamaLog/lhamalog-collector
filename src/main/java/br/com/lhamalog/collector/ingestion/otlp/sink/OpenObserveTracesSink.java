// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces destination: OpenObserve stream {@code traces} (configurable), replacing the old Redis sink (T032,
 * T041). Same confirmation rule as logs.
 *
 * <p>Besides ids, name and times, each document keeps {@code kind} and {@code statusCode} as OTLP enum names
 * ({@code SPAN_KIND_SERVER}, {@code STATUS_CODE_ERROR}), {@code statusMessage} when present,
 * {@code traceState} when present, {@code events} and {@code links} as JSON text, and the resource and scope
 * context. OpenObserve stores field names in lowercase; see {@code specs/001-otlp-receiver/data-model.md}.
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
	public void deliver(String tenantId, String productId, List<Scoped<Span>> acceptedSpans) {
		Instant receivedAt = clock.instant();
		long receivedMicros = OtlpValues.micros(receivedAt);
		List<Map<String, Object>> documents = new ArrayList<>(acceptedSpans.size());
		for (Scoped<Span> scoped : acceptedSpans) {
			Span span = scoped.record();
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
			if (!span.getTraceState().isEmpty()) {
				doc.put("traceState", span.getTraceState());
			}
			doc.put("name", span.getName());
			doc.put("kind", enumName(span.getKind() == Span.SpanKind.UNRECOGNIZED ? null : span.getKind().name(),
					span.getKindValue()));
			doc.put("startTimeUnixNano", Long.toUnsignedString(span.getStartTimeUnixNano()));
			doc.put("endTimeUnixNano", Long.toUnsignedString(span.getEndTimeUnixNano()));
			Status.StatusCode code = span.getStatus().getCode();
			doc.put("statusCode", enumName(code == Status.StatusCode.UNRECOGNIZED ? null : code.name(),
					span.getStatus().getCodeValue()));
			if (!span.getStatus().getMessage().isEmpty()) {
				doc.put("statusMessage", span.getStatus().getMessage());
			}
			doc.put("attributes", OtlpValues.attributesJson(span.getAttributesList()));
			doc.put("events", OtlpValues.json(events(span)));
			doc.put("links", OtlpValues.json(links(span)));
			OtlpValues.putContext(doc, scoped);
			documents.add(doc);
		}
		client.ingest(stream, documents);
	}

	private static String enumName(String name, int value) {
		return name != null ? name : Integer.toString(value);
	}

	private static List<Map<String, Object>> events(Span span) {
		List<Map<String, Object>> events = new ArrayList<>(span.getEventsCount());
		for (Span.Event event : span.getEventsList()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("timeUnixNano", Long.toUnsignedString(event.getTimeUnixNano()));
			item.put("name", event.getName());
			item.put("attributes", OtlpValues.toMap(event.getAttributesList()));
			events.add(item);
		}
		return events;
	}

	private static List<Map<String, Object>> links(Span span) {
		List<Map<String, Object>> links = new ArrayList<>(span.getLinksCount());
		for (Span.Link link : span.getLinksList()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("traceId", OtlpValues.hex(link.getTraceId()));
			item.put("spanId", OtlpValues.hex(link.getSpanId()));
			if (!link.getTraceState().isEmpty()) {
				item.put("traceState", link.getTraceState());
			}
			item.put("attributes", OtlpValues.toMap(link.getAttributesList()));
			links.add(item);
		}
		return links;
	}

}
