// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Duration;
import java.time.Instant;
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
		BatchAssessment<Scoped<LogRecord>> result = LogRecordAssessment.assess(request, TimestampWindow.DEFAULT,
				Instant.now());
		assertThat(result.valid()).hasSize(2);
		assertThat(result.rejectedCount()).isEqualTo(2);
	}

	private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

	private static long nanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	@Test
	void timestampWindowUsesTimeThenObservedTime() {
		TimestampWindow window = TimestampWindow.DEFAULT;
		long old = nanos(NOW.minus(Duration.ofHours(5)));
		long recent = nanos(NOW.minus(Duration.ofMinutes(1)));
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setTimeUnixNano(recent).build(), window, NOW)).isTrue();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setTimeUnixNano(old).build(), window, NOW)).isFalse();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setObservedTimeUnixNano(old).build(), window, NOW))
			.isFalse();
		assertThat(LogRecordAssessment.isValid(LogRecord.newBuilder().setObservedTimeUnixNano(recent).build(), window, NOW))
			.isTrue();
		assertThat(LogRecordAssessment.isValid(LogRecord.getDefaultInstance(), window, NOW)).isTrue();
	}

	@Test
	void effectiveTimeFallsBackFromTimeToObservedToZero() {
		assertThat(LogRecordAssessment.effectiveTimeUnixNano(
				LogRecord.newBuilder().setTimeUnixNano(5).setObservedTimeUnixNano(7).build())).isEqualTo(5);
		assertThat(LogRecordAssessment.effectiveTimeUnixNano(LogRecord.newBuilder().setObservedTimeUnixNano(7).build()))
			.isEqualTo(7);
		assertThat(LogRecordAssessment.effectiveTimeUnixNano(LogRecord.getDefaultInstance())).isZero();
	}

	@Test
	void acceptedRecordsKeepTheirResourceAndScope() {
		Resource resource = Resource.newBuilder()
			.addAttributes(KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder().setStringValue("api")))
			.build();
		InstrumentationScope scope = InstrumentationScope.newBuilder().setName("lib").setVersion("1.0").build();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder()
				.setResource(resource)
				.addScopeLogs(ScopeLogs.newBuilder().setScope(scope).addLogRecords(LogRecord.getDefaultInstance())))
			.build();
		BatchAssessment<Scoped<LogRecord>> result = LogRecordAssessment.assess(request, TimestampWindow.DEFAULT, NOW);
		assertThat(result.valid()).singleElement().satisfies(scoped -> {
			assertThat(scoped.resource()).isEqualTo(resource);
			assertThat(scoped.scope()).isEqualTo(scope);
		});
	}

}
