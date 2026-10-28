package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;

/** Destino de um lote de spans ja autenticado e validado. Mesmo contrato de {@link LogsBatchSink}. */
public interface TracesBatchSink {

    void accept(AuthenticatedProduct product, ExportTraceServiceRequest acceptedBatch, int spanCount);
}
