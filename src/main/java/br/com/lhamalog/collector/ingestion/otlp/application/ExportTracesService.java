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

	private final QuotaGate<ExportTraceServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.trace.v1.Span>> quota;
	private final BatchSanitizer<ExportTraceServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.trace.v1.Span>> sanitizer;

	public ExportTracesService(TokenValidator tokens, TracesSink sink) {
		this(tokens, sink, TimestampWindow.DEFAULT, Clock.systemUTC(), null);
	}

	public ExportTracesService(TokenValidator tokens, TracesSink sink, TimestampWindow window, Clock clock) {
		this(tokens, sink, window, clock, null);
	}

	public ExportTracesService(TokenValidator tokens, TracesSink sink, TimestampWindow window, Clock clock,
			QuotaGate<ExportTraceServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.trace.v1.Span>> quota) {
		this(tokens, sink, window, clock, quota, BatchSanitizer.identity());
	}

	public ExportTracesService(TokenValidator tokens, TracesSink sink, TimestampWindow window, Clock clock,
			QuotaGate<ExportTraceServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.trace.v1.Span>> quota,
			BatchSanitizer<ExportTraceServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.trace.v1.Span>> sanitizer) {
		this.tokens = tokens;
		this.sink = sink;
		this.window = window;
		this.clock = clock;
		this.quota = quota;
		this.sanitizer = sanitizer;
	}

	public IngestionDecision export(String bearerToken, OtlpPayload<ExportTraceServiceRequest> payload) {
		return BatchIngestion.run(tokens, bearerToken, payload,
				request -> SpanAssessment.assess(request, window, clock.instant()),
				(tenantId, productId, request, accepted) -> sink.deliver(tenantId, productId, request, accepted),
				"spans",
				SpanAssessment.REJECTION_REASON, quota, sanitizer);
	}

}
