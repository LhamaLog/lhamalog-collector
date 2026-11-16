package br.com.lhamalog.collector.usage.domain;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.trace.v1.Span;
import java.time.Clock;
import java.util.List;

/** Builds UsageFact instances from accepted OTLP logs or traces using the canonical uncompressed protobuf size, including resource, scope and envelope. */
public final class UsageFactFactory {

	private final Clock clock;

	public UsageFactFactory(Clock clock) {
		this.clock = clock;
	}

	public UsageFact logs(String tenantId, String productId, ExportLogsServiceRequest request,
			List<Scoped<LogRecord>> accepted) {
		CanonicalOtlpMeasurement measurement = CanonicalOtlpMeasurement.logs(request, accepted);
		return UsageFact.create(tenantId, productId, UsageDomain.LOGS,
				br.com.lhamalog.collector.ingestion.otlp.sink.RecordIds.of(tenantId, productId, accepted),
				measurement.serializedBytes(), clock.instant());
	}

	public UsageFact logs(String tenantId, String productId, ExportLogsServiceRequest request,
			List<Scoped<LogRecord>> accepted, StoredBatch batch) {
		requireSignal(batch, UsageDomain.LOGS);
		CanonicalOtlpMeasurement measurement = CanonicalOtlpMeasurement.logs(request, accepted);
		return UsageFact.create(tenantId, productId, UsageDomain.LOGS, batch.recordIds(),
				measurement.serializedBytes(), clock.instant());
	}

	public UsageFact traces(String tenantId, String productId, ExportTraceServiceRequest request,
			List<Scoped<Span>> accepted) {
		CanonicalOtlpMeasurement measurement = CanonicalOtlpMeasurement.traces(request, accepted);
		return UsageFact.create(tenantId, productId, UsageDomain.TRACES,
				br.com.lhamalog.collector.ingestion.otlp.sink.RecordIds.of(tenantId, productId, accepted),
				measurement.serializedBytes(), clock.instant());
	}

	public UsageFact traces(String tenantId, String productId, ExportTraceServiceRequest request,
			List<Scoped<Span>> accepted, StoredBatch batch) {
		requireSignal(batch, UsageDomain.TRACES);
		CanonicalOtlpMeasurement measurement = CanonicalOtlpMeasurement.traces(request, accepted);
		return UsageFact.create(tenantId, productId, UsageDomain.TRACES, batch.recordIds(),
				measurement.serializedBytes(), clock.instant());
	}

	private static void requireSignal(StoredBatch batch, UsageDomain domain) {
		if (batch == null || !batch.signal().name().equals(domain.name())) {
			throw new IllegalArgumentException("stored batch signal does not match usage domain");
		}
	}
}
