// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.LogRecordAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.TimestampWindow;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import java.time.Clock;

/**
 * Use case of a log export, independent of HTTP or gRPC (T018, T025, T040). The {@code productId} written is
 * always the one of the token; the batch content never overrides it. Records whose time is outside the
 * {@link TimestampWindow} are rejected per record.
 */
public class ExportLogsService {

	private final TokenValidator tokens;

	private final LogsSink sink;

	private final TimestampWindow window;

	private final Clock clock;

	private final QuotaGate<ExportLogsServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> quota;
	private final BatchSanitizer<ExportLogsServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> sanitizer;

	public ExportLogsService(TokenValidator tokens, LogsSink sink) {
		this(tokens, sink, TimestampWindow.DEFAULT, Clock.systemUTC(), null);
	}

	public ExportLogsService(TokenValidator tokens, LogsSink sink, TimestampWindow window, Clock clock) {
		this(tokens, sink, window, clock, null);
	}

	public ExportLogsService(TokenValidator tokens, LogsSink sink, TimestampWindow window, Clock clock,
			QuotaGate<ExportLogsServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> quota) {
		this(tokens, sink, window, clock, quota, BatchSanitizer.identity());
	}

	public ExportLogsService(TokenValidator tokens, LogsSink sink, TimestampWindow window, Clock clock,
			QuotaGate<ExportLogsServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> quota,
			BatchSanitizer<ExportLogsServiceRequest, br.com.lhamalog.collector.ingestion.otlp.domain.Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> sanitizer) {
		this.tokens = tokens;
		this.sink = sink;
		this.window = window;
		this.clock = clock;
		this.quota = quota;
		this.sanitizer = sanitizer;
	}

	public IngestionDecision export(String bearerToken, OtlpPayload<ExportLogsServiceRequest> payload) {
		return BatchIngestion.run(tokens, bearerToken, payload,
				request -> LogRecordAssessment.assess(request, window, clock.instant()),
				(tenantId, productId, request, accepted) -> sink.deliver(tenantId, productId, request, accepted),
				"log records",
				LogRecordAssessment.REJECTION_REASON, quota, sanitizer);
	}

}
