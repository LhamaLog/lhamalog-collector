package br.com.lhamalog.collector.usage.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class UsageMeasurementSinkIdempotencyIntegrationTest {

	@Container
	static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static MongoClient client;
	private static MongoUsageOutboxRepository repository;

	@BeforeAll
	static void setUp() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		repository = new MongoUsageOutboxRepository(
				new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "usage_sink_idempotency")));
	}

	@AfterAll
	static void tearDown() {
		client.close();
	}

	@Test
	void measuringTheSameBatchTwiceThroughTheSinkKeepsOneOutboxItemDespiteDifferentClocks() {
		Instant firstClock = Instant.parse("2026-11-09T00:00:00Z");
		MutableClock clock = new MutableClock(firstClock);
		UsageFactRecorder recorder = new UsageFactRecorder(repository, clock);
		LogRecord record = LogRecord.newBuilder().setSeverityNumberValue(9).build();
		Resource resource = Resource.getDefaultInstance();
		InstrumentationScope scope = InstrumentationScope.getDefaultInstance();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().setResource(resource)
						.addScopeLogs(ScopeLogs.newBuilder().setScope(scope).addLogRecords(record)))
				.build();
		StoredBatch stored = new StoredBatch(Signal.LOGS, "org", "logs", "oo-1", List.of("sink-record-1"), 1L, 1L,
				"2026-11-09T00:00:00Z");
		BatchStore<LogRecord> store = (tenant, product, records) -> stored;
		PublishingLogsSink sink = new PublishingLogsSink(store, (tenant, product, batch, records) -> {
		}, new UsageFactFactory(clock), recorder, (tenant, product, batch, records, key) -> "DONE:test");
		List<Scoped<LogRecord>> accepted = List.of(new Scoped<>(resource, scope, record));

		sink.deliver("tenant-sink", "product-sink", request, accepted);
		clock.set(Instant.parse("2026-11-09T00:00:07Z"));
		sink.deliver("tenant-sink", "product-sink", request, accepted);

		String usageFactId = UsageFact.create("tenant-sink", "product-sink", UsageDomain.LOGS,
				List.of("sink-record-1"), 0, Instant.EPOCH).usageFactId();
		UsageOutboxItem item = repository.findById(usageFactId).orElseThrow();
		assertThat(item.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(item.fact().acceptedAt()).isEqualTo(firstClock);
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}

