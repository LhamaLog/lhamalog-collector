// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.SpanAssessment;
import br.com.lhamalog.collector.ingestion.otlp.sink.TracesSink;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;

/** Use case of a trace export (T033). Same decisions as logs, counted in spans. */
public class ExportTracesService {

	private final TokenValidator tokens;

	private final TracesSink sink;

	public ExportTracesService(TokenValidator tokens, TracesSink sink) {
		this.tokens = tokens;
		this.sink = sink;
	}

	public IngestionDecision export(String bearerToken, OtlpPayload<ExportTraceServiceRequest> payload) {
		return BatchIngestion.run(tokens, bearerToken, payload, SpanAssessment::assess, sink::deliver, "spans",
				SpanAssessment.REJECTION_REASON);
	}

}
