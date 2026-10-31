// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.Test;

class LogRecordAssessmentTest {

	private static ByteString bytes(int size) {
		return ByteString.copyFrom(new byte[size]);
	}

	@Test
	void logWithoutBodyIsValid() {
		assertThat(LogRecordAssessment.isValid(LogRecord.getDefaultInstance())).isTrue();
	}

	@Test
	void traceIdPresentMustHave16Bytes() {
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setTraceId(bytes(16)).build())).isTrue();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setTraceId(bytes(15)).build())).isFalse();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setTraceId(bytes(17)).build())).isFalse();
	}

	@Test
	void spanIdPresentMustHave8Bytes() {
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setSpanId(bytes(8)).build())).isTrue();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setSpanId(bytes(7)).build())).isFalse();
	}

	@Test
	void severityNumberMustBeWithin0And24() {
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setSeverityNumberValue(24).build())).isTrue();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setSeverityNumberValue(25).build())).isFalse();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setSeverityNumberValue(-1).build())).isFalse();
	}

	@Test
	void rejectionIsCountedPerRecordAcrossResourcesAndScopes() {
		LogRecord valid = LogRecord.getDefaultInstance();
		LogRecord invalid = LogRecord.newBuilder().setSpanId(bytes(3)).build();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(valid).addLogRecords(invalid))
				.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(invalid)))
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(valid)))
			.build();
		BatchAssessment<LogRecord> result = LogRecordAssessment.assess(request);
		assertThat(result.valid()).hasSize(2);
		assertThat(result.rejectedCount()).isEqualTo(2);
	}

}
