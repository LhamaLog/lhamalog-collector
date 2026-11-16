package br.com.lhamalog.collector.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalLogsMeasurementTest {

	@Test
	void preservesTheOriginalEnvelopeOrderAndRemovesEmptyGroups() {
		LogRecord kept = LogRecord.newBuilder().setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
				.setStringValue("kept")).build();
		LogRecord rejected = LogRecord.newBuilder().setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
				.setStringValue("rejected")).build();
		ResourceLogs resource = ResourceLogs.newBuilder()
			.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(kept))
			.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(rejected))
			.build();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder().addResourceLogs(resource).build();

		CanonicalOtlpMeasurement measurement = CanonicalOtlpMeasurement.logs(request,
				List.of(new Scoped<>(resource.getResource(), resource.getScopeLogs(0).getScope(), kept)));
		ExportLogsServiceRequest rebuilt = (ExportLogsServiceRequest) measurement.acceptedRequest();

		assertThat(rebuilt.getResourceLogsCount()).isOne();
		assertThat(rebuilt.getResourceLogs(0).getScopeLogsCount()).isOne();
		assertThat(rebuilt.getResourceLogs(0).getScopeLogs(0).getLogRecordsList()).containsExactly(kept);
		assertThat(measurement.serializedBytes()).isPositive();
	}
}
