package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementacao provisoria (LLT-181) dos sinks: apenas registra em log e contabiliza.
 * Sera substituida pela publicacao na fila (LLT-184).
 */
public class LoggingBatchSink implements LogsBatchSink, TracesBatchSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingBatchSink.class);

    private final AtomicLong logRecords = new AtomicLong();
    private final AtomicLong spans = new AtomicLong();

    @Override
    public void accept(AuthenticatedProduct product, ExportLogsServiceRequest acceptedBatch, int logRecordCount) {
        long total = logRecords.addAndGet(logRecordCount);
        log.debug("[OTLP] lote de logs aceito | token={} | registros={} | total={}", product.tokenId(), logRecordCount, total);
    }

    @Override
    public void accept(AuthenticatedProduct product, ExportTraceServiceRequest acceptedBatch, int spanCount) {
        long total = spans.addAndGet(spanCount);
        log.debug("[OTLP] lote de traces aceito | token={} | spans={} | total={}", product.tokenId(), spanCount, total);
    }

    public long acceptedLogRecords() {
        return logRecords.get();
    }

    public long acceptedSpans() {
        return spans.get();
    }
}
