package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;

/**
 * Destino de um lote de logs ja autenticado e validado (somente registros aceitos).
 *
 * <p>Ponto de extensao para as proximas historias: LLT-182 (tenant/produto), LLT-183 (limites por plano),
 * LLT-184 (publicar na fila) e LLT-185 (persistir). Para sinalizar back-pressure, lance
 * {@link SinkUnavailableException}.
 */
public interface LogsBatchSink {

    void accept(AuthenticatedProduct product, ExportLogsServiceRequest acceptedBatch, int logRecordCount);
}
