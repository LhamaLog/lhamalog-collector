// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.ArrayList;
import java.util.List;

/**
 * Which log records are valid (T016, research.md section 8).
 *
 * <p>A log is invalid when {@code trace_id} is present with a size other than 16 bytes, when
 * {@code span_id} is present with a size other than 8 bytes, or when {@code severity_number} is outside
 * 0..24. A log without body stays valid.
 */
public final class LogRecordAssessment {

	public static final String REJECTION_REASON = "invalid trace_id, span_id or severity_number";

	private LogRecordAssessment() {
	}

	public static boolean isValid(LogRecord log) {
		if (!log.getTraceId().isEmpty() && log.getTraceId().size() != 16) {
			return false;
		}
		if (!log.getSpanId().isEmpty() && log.getSpanId().size() != 8) {
			return false;
		}
		int severity = log.getSeverityNumberValue();
		return severity >= 0 && severity <= 24;
	}

	public static BatchAssessment<LogRecord> assess(ExportLogsServiceRequest request) {
		List<LogRecord> valid = new ArrayList<>();
		int rejected = 0;
		for (ResourceLogs resource : request.getResourceLogsList()) {
			for (ScopeLogs scope : resource.getScopeLogsList()) {
				for (LogRecord log : scope.getLogRecordsList()) {
					if (isValid(log)) {
						valid.add(log);
					}
					else {
						rejected++;
					}
				}
			}
		}
		return new BatchAssessment<>(valid, rejected);
	}

}
