// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Which spans are valid (T031, research.md section 8).
 *
 * <p>A span is invalid when {@code trace_id} is absent, not 16 bytes or all zeros; when {@code span_id} is
 * absent, not 8 bytes or all zeros; when {@code parent_span_id} is present and not 8 bytes; when
 * {@code name} is empty; or when {@code end_time_unix_nano} is not zero and before
 * {@code start_time_unix_nano}.
 *
 * <p>A span is also invalid when {@code start_time_unix_nano}, which becomes {@code _timestamp}, is outside
 * the {@link TimestampWindow} (T040, FR-019). A zero start time uses the accept instant.
 */
public final class SpanAssessment {

	public static final String REJECTION_REASON = "invalid trace_id, span_id, parent_span_id, name, end time or start time outside the accepted window";

	private SpanAssessment() {
	}

	public static boolean isValid(Span span) {
		if (!isNonZeroId(span.getTraceId(), 16) || !isNonZeroId(span.getSpanId(), 8)) {
			return false;
		}
		if (!span.getParentSpanId().isEmpty() && span.getParentSpanId().size() != 8) {
			return false;
		}
		if (span.getName().isEmpty()) {
			return false;
		}
		long end = span.getEndTimeUnixNano();
		return end == 0 || Long.compareUnsigned(end, span.getStartTimeUnixNano()) >= 0;
	}

	/** Structural rules plus the timestamp window on the start time. */
	public static boolean isValid(Span span, TimestampWindow window, Instant now) {
		return isValid(span) && window.accepts(span.getStartTimeUnixNano(), now);
	}

	public static BatchAssessment<Scoped<Span>> assess(ExportTraceServiceRequest request, TimestampWindow window,
			Instant now) {
		List<Scoped<Span>> valid = new ArrayList<>();
		int rejected = 0;
		for (ResourceSpans resource : request.getResourceSpansList()) {
			for (ScopeSpans scope : resource.getScopeSpansList()) {
				for (Span span : scope.getSpansList()) {
					if (isValid(span, window, now)) {
						valid.add(new Scoped<>(resource.getResource(), scope.getScope(), span));
					}
					else {
						rejected++;
					}
				}
			}
		}
		return new BatchAssessment<>(valid, rejected);
	}

	private static boolean isNonZeroId(ByteString id, int size) {
		if (id.size() != size) {
			return false;
		}
		for (int i = 0; i < size; i++) {
			if (id.byteAt(i) != 0) {
				return true;
			}
		}
		return false;
	}

}
