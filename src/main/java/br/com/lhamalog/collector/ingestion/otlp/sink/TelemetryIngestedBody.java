// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code log.ingested} (schema 1) and {@code trace.ingested} (schema 1): only a reference to the batch
 * in OpenObserve, never the log or span content. {@code tenantId} and {@code productId} travel in the
 * envelope metadata; {@code productId} is repeated here for the Varredura to group by product.
 */
public record TelemetryIngestedBody(String signal, String productId, OpenObserveRef openobserve,
		List<String> serviceNames, String priority, String criticality, int maxSeverityNumber,
		Map<String, Integer> severityCounts, int recordCount, long bytes, String receivedAt) {

	/** Where the Finder loads the content: {@code POST /api/{organization}/_search} on {@code stream}. */
	public record OpenObserveRef(String organization, String stream, String batchId, String recordId,
			long minTimestampMicros, long maxTimestampMicros) {
	}

}
