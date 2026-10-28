package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Caso de uso comum aos adaptadores HTTP e gRPC: valida os registros, separa os aceitos dos rejeitados,
 * entrega os aceitos ao sink e monta a resposta OTLP (com partial_success quando houver rejeicao).
 * A autenticacao acontece antes, no adaptador, e um lote nao autenticado nunca chega aqui.
 */
public class OtlpIngestionService {

    private final LogsBatchSink logsSink;
    private final TracesBatchSink tracesSink;

    public OtlpIngestionService(LogsBatchSink logsSink, TracesBatchSink tracesSink) {
        this.logsSink = logsSink;
        this.tracesSink = tracesSink;
    }

    public ExportLogsServiceResponse exportLogs(AuthenticatedProduct product, ExportLogsServiceRequest request) {
        Set<String> errors = new LinkedHashSet<>();
        long rejected = 0;
        int accepted = 0;
        ExportLogsServiceRequest.Builder out = ExportLogsServiceRequest.newBuilder();
        for (ResourceLogs rl : request.getResourceLogsList()) {
            ResourceLogs.Builder rlb = rl.toBuilder().clearScopeLogs();
            for (ScopeLogs sl : rl.getScopeLogsList()) {
                List<LogRecord> ok = new ArrayList<>();
                for (LogRecord r : sl.getLogRecordsList()) {
                    String error = OtlpRecordValidator.validate(r);
                    if (error == null) {
                        ok.add(r);
                    } else {
                        rejected++;
                        errors.add(error);
                    }
                }
                if (!ok.isEmpty()) {
                    rlb.addScopeLogs(sl.toBuilder().clearLogRecords().addAllLogRecords(ok));
                    accepted += ok.size();
                }
            }
            if (rlb.getScopeLogsCount() > 0) {
                out.addResourceLogs(rlb);
            }
        }
        if (accepted > 0) {
            logsSink.accept(product, out.build(), accepted);
        }
        ExportLogsServiceResponse.Builder response = ExportLogsServiceResponse.newBuilder();
        if (rejected > 0) {
            response.setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
                    .setRejectedLogRecords(rejected).setErrorMessage(summary(errors)));
        }
        return response.build();
    }

    public ExportTraceServiceResponse exportTraces(AuthenticatedProduct product, ExportTraceServiceRequest request) {
        Set<String> errors = new LinkedHashSet<>();
        long rejected = 0;
        int accepted = 0;
        ExportTraceServiceRequest.Builder out = ExportTraceServiceRequest.newBuilder();
        for (ResourceSpans rs : request.getResourceSpansList()) {
            ResourceSpans.Builder rsb = rs.toBuilder().clearScopeSpans();
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                List<Span> ok = new ArrayList<>();
                for (Span s : ss.getSpansList()) {
                    String error = OtlpRecordValidator.validate(s);
                    if (error == null) {
                        ok.add(s);
                    } else {
                        rejected++;
                        errors.add(error);
                    }
                }
                if (!ok.isEmpty()) {
                    rsb.addScopeSpans(ss.toBuilder().clearSpans().addAllSpans(ok));
                    accepted += ok.size();
                }
            }
            if (rsb.getScopeSpansCount() > 0) {
                out.addResourceSpans(rsb);
            }
        }
        if (accepted > 0) {
            tracesSink.accept(product, out.build(), accepted);
        }
        ExportTraceServiceResponse.Builder response = ExportTraceServiceResponse.newBuilder();
        if (rejected > 0) {
            response.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                    .setRejectedSpans(rejected).setErrorMessage(summary(errors)));
        }
        return response.build();
    }

    private static String summary(Set<String> errors) {
        return String.join("; ", errors);
    }
}
