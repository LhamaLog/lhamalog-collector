package br.com.lhamalog.collector.ingestion.otlp.application;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;

/**
 * Regras minimas de validade por registro (LLT-181). Registro invalido e rejeitado individualmente e
 * contabilizado em partial_success; o restante do lote segue.
 */
final class OtlpRecordValidator {

    private OtlpRecordValidator() {
    }

    /** @return mensagem de erro, ou null se valido. */
    static String validate(LogRecord r) {
        if (!r.getTraceId().isEmpty() && r.getTraceId().size() != 16) {
            return "log record com trace_id invalido (esperado 16 bytes)";
        }
        if (!r.getSpanId().isEmpty() && r.getSpanId().size() != 8) {
            return "log record com span_id invalido (esperado 8 bytes)";
        }
        int severity = r.getSeverityNumberValue();
        if (severity < 0 || severity > 24) {
            return "log record com severity_number fora do intervalo 0..24";
        }
        return null;
    }

    static String validate(Span s) {
        if (s.getTraceId().size() != 16 || allZero(s.getTraceId())) {
            return "span com trace_id invalido (esperado 16 bytes, nao nulo)";
        }
        if (s.getSpanId().size() != 8 || allZero(s.getSpanId())) {
            return "span com span_id invalido (esperado 8 bytes, nao nulo)";
        }
        if (!s.getParentSpanId().isEmpty() && s.getParentSpanId().size() != 8) {
            return "span com parent_span_id invalido (esperado 8 bytes)";
        }
        if (s.getName().isEmpty()) {
            return "span sem name";
        }
        if (s.getEndTimeUnixNano() != 0 && Long.compareUnsigned(s.getEndTimeUnixNano(), s.getStartTimeUnixNano()) < 0) {
            return "span com end_time_unix_nano anterior a start_time_unix_nano";
        }
        return null;
    }

    private static boolean allZero(ByteString bytes) {
        for (int i = 0; i < bytes.size(); i++) {
            if (bytes.byteAt(i) != 0) {
                return false;
            }
        }
        return true;
    }
}
