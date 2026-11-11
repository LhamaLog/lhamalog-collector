package br.com.lhamalog.collector.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageFactFactoryTest {

	@Test
	void usesTheStoredAcceptedIdsAndAnUtcAcceptanceInstant() {
		LogRecord record = LogRecord.newBuilder().build();
		ResourceLogs resource = ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)).build();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder().addResourceLogs(resource).build();
		StoredBatch batch = new StoredBatch(Signal.LOGS, "org", "logs", "openobserve-id", List.of("record-1"),
				1, 1, "2026-11-09T00:00:00Z");
		Instant now = Instant.parse("2026-11-09T01:02:03Z");

		UsageFact fact = new UsageFactFactory(Clock.fixed(now, ZoneOffset.UTC)).logs("tenant", "product", request,
				List.of(new Scoped<>(resource.getResource(), resource.getScopeLogs(0).getScope(), record)), batch);

		assertThat(fact.domain()).isEqualTo(UsageDomain.LOGS);
		assertThat(fact.acceptedRecordIds()).containsExactly("record-1");
		assertThat(fact.acceptedAt()).isEqualTo(now);
		assertThat(fact.acceptedBytes()).isPositive();
	}
}
