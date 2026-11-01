// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Which log records are valid (T016, research.md section 8).
 *
 * <p>A log is invalid when {@code trace_id} is present with a size other than 16 bytes, when
 * {@code span_id} is present with a size other than 8 bytes, or when {@code severity_number} is outside
 * 0..24. A log without body stays valid.
 *
 * <p>A log is also invalid when its effective time ({@link #effectiveTimeUnixNano(LogRecord)}) is outside
 * the {@link TimestampWindow} (T040, FR-019). Without {@code time_unix_nano} nor
 * {@code observed_time_unix_nano} the accept instant is used, which is always inside the window.
 */
public final class LogRecordAssessment {

	public static final String REJECTION_REASON = "invalid trace_id, span_id, severity_number or timestamp outside the accepted window";

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

	/** Structural rules plus the timestamp window. */
	public static boolean isValid(LogRecord log, TimestampWindow window, Instant now) {
		return isValid(log) && window.accepts(effectiveTimeUnixNano(log), now);
	}

	/**
	 * Time that becomes {@code _timestamp} (T043, FR-022): {@code time_unix_nano}, else
	 * {@code observed_time_unix_nano}, else zero, meaning "use the accept instant".
	 */
	public static long effectiveTimeUnixNano(LogRecord log) {
		return log.getTimeUnixNano() != 0 ? log.getTimeUnixNano() : log.getObservedTimeUnixNano();
	}

	public static BatchAssessment<Scoped<LogRecord>> assess(ExportLogsServiceRequest request, TimestampWindow window,
			Instant now) {
		List<Scoped<LogRecord>> valid = new ArrayList<>();
		int rejected = 0;
		for (ResourceLogs resource : request.getResourceLogsList()) {
			for (ScopeLogs scope : resource.getScopeLogsList()) {
				for (LogRecord log : scope.getLogRecordsList()) {
					if (isValid(log, window, now)) {
						valid.add(new Scoped<>(resource.getResource(), scope.getScope(), log));
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
