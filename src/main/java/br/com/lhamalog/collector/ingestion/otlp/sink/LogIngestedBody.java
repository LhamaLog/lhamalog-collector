// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.util.Map;

/**
 * Body of the {@code log.ingested} event (schema version 1). It keeps the OTLP log, resource and scope as
 * they arrived. {@code tenantId} and {@code productId} are not here: they travel in the envelope metadata.
 * Absent OTLP fields are {@code null}; nanosecond times are unsigned decimal strings.
 */
public record LogIngestedBody(String receivedAt, String timeUnixNano, String observedTimeUnixNano,
		int severityNumber, String severityText, String body, Map<String, Object> attributes, String traceId,
		String spanId, long flags, Resource resource, Scope scope) {

	public record Resource(String serviceName, Map<String, Object> attributes) {
	}

	public record Scope(String name, String version, Map<String, Object> attributes) {
	}

}
