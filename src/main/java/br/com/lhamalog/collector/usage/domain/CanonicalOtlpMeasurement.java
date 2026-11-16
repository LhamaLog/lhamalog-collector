package br.com.lhamalog.collector.usage.domain;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bytes used for usage are a deterministic protobuf serialization of the
 * original OTLP envelope rebuilt with accepted records only.
 */
public record CanonicalOtlpMeasurement(UsageDomain domain, MessageLite acceptedRequest, long serializedBytes) {

	public static CanonicalOtlpMeasurement logs(ExportLogsServiceRequest request,
			List<Scoped<io.opentelemetry.proto.logs.v1.LogRecord>> accepted) {
		Map<Scoped<io.opentelemetry.proto.logs.v1.LogRecord>, Integer> keep = occurrences(accepted);
		ExportLogsServiceRequest.Builder output = ExportLogsServiceRequest.newBuilder();
		for (ResourceLogs resource : request.getResourceLogsList()) {
			ResourceLogs.Builder rebuiltResource = ResourceLogs.newBuilder().setResource(resource.getResource())
				.setSchemaUrl(resource.getSchemaUrl());
			for (ScopeLogs scope : resource.getScopeLogsList()) {
				List<io.opentelemetry.proto.logs.v1.LogRecord> records = new ArrayList<>();
				for (io.opentelemetry.proto.logs.v1.LogRecord record : scope.getLogRecordsList()) {
					if (take(keep, new Scoped<>(resource.getResource(), scope.getScope(), record))) {
						records.add(record);
					}
				}
				if (!records.isEmpty()) {
					rebuiltResource.addScopeLogs(ScopeLogs.newBuilder().setScope(scope.getScope())
						.setSchemaUrl(scope.getSchemaUrl()).addAllLogRecords(records));
				}
			}
			if (rebuiltResource.getScopeLogsCount() > 0) {
				output.addResourceLogs(rebuiltResource);
			}
		}
		return of(UsageDomain.LOGS, output.build());
	}

	public static CanonicalOtlpMeasurement traces(ExportTraceServiceRequest request,
			List<Scoped<io.opentelemetry.proto.trace.v1.Span>> accepted) {
		Map<Scoped<io.opentelemetry.proto.trace.v1.Span>, Integer> keep = occurrences(accepted);
		ExportTraceServiceRequest.Builder output = ExportTraceServiceRequest.newBuilder();
		for (ResourceSpans resource : request.getResourceSpansList()) {
			ResourceSpans.Builder rebuiltResource = ResourceSpans.newBuilder().setResource(resource.getResource())
				.setSchemaUrl(resource.getSchemaUrl());
			for (ScopeSpans scope : resource.getScopeSpansList()) {
				List<io.opentelemetry.proto.trace.v1.Span> spans = new ArrayList<>();
				for (io.opentelemetry.proto.trace.v1.Span span : scope.getSpansList()) {
					if (take(keep, new Scoped<>(resource.getResource(), scope.getScope(), span))) {
						spans.add(span);
					}
				}
				if (!spans.isEmpty()) {
					rebuiltResource.addScopeSpans(ScopeSpans.newBuilder().setScope(scope.getScope())
						.setSchemaUrl(scope.getSchemaUrl()).addAllSpans(spans));
				}
			}
			if (rebuiltResource.getScopeSpansCount() > 0) {
				output.addResourceSpans(rebuiltResource);
			}
		}
		return of(UsageDomain.TRACES, output.build());
	}

	private static CanonicalOtlpMeasurement of(UsageDomain domain, MessageLite request) {
		return new CanonicalOtlpMeasurement(domain, request, deterministicBytes(request).length);
	}

	private static byte[] deterministicBytes(MessageLite request) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(request.getSerializedSize());
			CodedOutputStream output = CodedOutputStream.newInstance(bytes);
			output.useDeterministicSerialization();
			request.writeTo(output);
			output.flush();
			return bytes.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not serialize OTLP request", ex);
		}
	}

	private static <T> Map<Scoped<T>, Integer> occurrences(List<Scoped<T>> records) {
		Map<Scoped<T>, Integer> result = new HashMap<>();
		for (Scoped<T> record : records) result.merge(record, 1, Integer::sum);
		return result;
	}

	private static <T> boolean take(Map<T, Integer> records, T record) {
		Integer count = records.get(record);
		if (count == null || count == 0) return false;
		if (count == 1) records.remove(record); else records.put(record, count - 1);
		return true;
	}
}
