// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.SpanAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.sink.TracesSink;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.time.Clock;

/** Use case of a trace export (T033, T040). Same decisions as logs, counted in spans. */
public class ExportTracesService {

	private final TokenValidator tokens;

	private final TracesSink sink;

	private final TimestampWindow window;

	private final Clock clock;

	public ExportTracesService(TokenValidator tokens, TracesSink sink) {
		this(tokens, sink, TimestampWindow.DEFAULT, Clock.systemUTC());
	}

	public ExportTracesService(TokenValidator tokens, TracesSink sink, TimestampWindow window, Clock clock) {
		this.tokens = tokens;
		this.sink = sink;
		this.window = window;
		this.clock = clock;
	}

	public IngestionDecision export(String bearerToken, OtlpPayload<ExportTraceServiceRequest> payload) {
		return BatchIngestion.run(tokens, bearerToken, payload,
				request -> SpanAssessment.assess(request, window, clock.instant()),
				(tenantId, productId, request, accepted) -> sink.deliver(tenantId, productId, request, accepted),
				"spans",
				SpanAssessment.REJECTION_REASON);
	}

}
