// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.domain.LogRecordAssessment;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;

/**
 * Use case of a log export, independent of HTTP or gRPC (T018, T025). The {@code productId} written is
 * always the one of the token; the batch content never overrides it.
 */
public class ExportLogsService {

	private final TokenValidator tokens;

	private final LogsSink sink;

	public ExportLogsService(TokenValidator tokens, LogsSink sink) {
		this.tokens = tokens;
		this.sink = sink;
	}

	public IngestionDecision export(String bearerToken, OtlpPayload<ExportLogsServiceRequest> payload) {
		return BatchIngestion.run(tokens, bearerToken, payload, LogRecordAssessment::assess, sink::deliver,
				"log records", LogRecordAssessment.REJECTION_REASON);
	}

}
