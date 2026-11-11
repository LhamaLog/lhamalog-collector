package br.com.lhamalog.collector.usage.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
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
class MongoUsageOutboxRepositoryTest {

	@Container
	static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static MongoClient client;

	private static MongoUsageOutboxRepository repository;

	@BeforeAll
	static void setUp() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		repository = new MongoUsageOutboxRepository(
				new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "usage_outbox_test")));
	}

	@AfterAll
	static void tearDown() {
		client.close();
	}

	@Test
	void storesAnImmutablePendingCandidateAndReturnsItForAnIdenticalRetry() {
		UsageFact fact = fact("record-1", 1);

		UsageOutboxItem stored = repository.insertPending(fact, Instant.parse("2026-11-09T00:00:00Z"));
		UsageOutboxItem retry = repository.insertPending(fact, Instant.parse("2026-11-09T00:00:01Z"));

		assertThat(stored.state()).isEqualTo(UsageOutboxState.PENDING);
		assertThat(retry).isEqualTo(stored);
		assertThat(repository.findById(fact.usageFactId())).contains(stored);
	}

	@Test
	void rejectsConflictingContentForTheSameImmutableId() {
		UsageFact fact = fact("record-2", 1);
		repository.insertPending(fact, Instant.parse("2026-11-09T00:00:00Z"));
		UsageFact divergent = new UsageFact(fact.usageFactId(), fact.batchId(), fact.tenantId(), fact.productId(),
				fact.domain(), List.of("different-record"), 1, 2, fact.acceptedAt(), fact.schemaVersion());

		assertThatThrownBy(() -> repository.insertPending(divergent, Instant.parse("2026-11-09T00:00:01Z")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("immutable content");
	}


	@Test
	void ignoresAcceptedAtWhenRecognizingAnIdenticalBatchRetry() {
		UsageFact first = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-clock"), 8,
				Instant.parse("2026-11-09T00:00:00Z"));
		UsageFact later = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-clock"), 8,
				Instant.parse("2026-11-09T00:00:05Z"));

		UsageOutboxItem stored = repository.insertPending(first, Instant.parse("2026-11-09T00:00:00Z"));
		UsageOutboxItem retry = repository.insertPending(later, Instant.parse("2026-11-09T00:00:05Z"));

		assertThat(first.sameImmutableContent(later)).isTrue();
		assertThat(first.acceptedAt()).isNotEqualTo(later.acceptedAt());
		assertThat(retry.id()).isEqualTo(stored.id());
		assertThat(retry.fact().acceptedAt()).isEqualTo(first.acceptedAt());
		assertThat(repository.findById(first.usageFactId())).contains(stored);
	}

	@Test
	void reopensAnAbandonedBatchSoTheResendCanBeMeasuredOnce() {
		UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-abandoned"), 3,
				Instant.parse("2026-11-09T00:00:00Z"));
		UsageOutboxItem pending = repository.insertPending(fact, Instant.parse("2026-11-09T00:00:00Z"));
		assertThat(repository.compareAndSet(pending, pending.abandon())).isTrue();

		UsageFact resend = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-abandoned"), 3,
				Instant.parse("2026-11-09T00:10:00Z"));
		UsageOutboxItem reopened = repository.insertPending(resend, Instant.parse("2026-11-09T00:10:00Z"));

		assertThat(reopened.state()).isEqualTo(UsageOutboxState.PENDING);
		assertThat(reopened.fact().acceptedAt()).isEqualTo(resend.acceptedAt());
		assertThat(reopened.expireAt()).isNull();
	}

	@Test
	void allowsANewMeasurementAfterTheSevenDayRetentionWindow() {
		UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-ttl"), 4,
				Instant.parse("2026-11-01T00:00:00Z"));
		UsageOutboxItem pending = repository.insertPending(fact, Instant.parse("2026-11-01T00:00:00Z"));
		UsageOutboxItem accepted = pending.accept();
		assertThat(repository.compareAndSet(pending, accepted)).isTrue();
		UsageOutboxItem synced = accepted.sync(Instant.parse("2026-11-01T00:00:01Z"));
		assertThat(repository.compareAndSet(accepted, synced)).isTrue();
		assertThat(repository.findById(fact.usageFactId()).orElseThrow().expireAt())
			.isEqualTo(Instant.parse("2026-11-08T00:00:01Z"));

		UsageFact later = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-ttl"), 4,
				Instant.parse("2026-11-09T00:00:00Z"));
		UsageOutboxItem renewed = repository.insertPending(later, Instant.parse("2026-11-09T00:00:00Z"));
		assertThat(renewed.state()).isEqualTo(UsageOutboxState.PENDING);
		assertThat(renewed.fact().acceptedAt()).isEqualTo(later.acceptedAt());
	}


	@Test
	void createsAnAbsoluteExpiryIndexForTerminalOutboxDocuments() {
		java.util.List<org.bson.Document> indexes = new java.util.ArrayList<>();
		client.getDatabase("usage_outbox_test").getCollection("usage_outbox").listIndexes().into(indexes);
		org.bson.Document ttl = indexes.stream()
				.filter(index -> "usage_outbox_expire_at".equals(index.getString("name")))
				.findFirst().orElseThrow();
		assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isZero();
	}

	@Test
	void retryStoresNextAttemptAtAsAQueryableBsonDate() {
		Instant created = Instant.parse("2026-11-09T00:00:00Z");
		UsageFact fact = fact("record-retry-date", 7);
		UsageOutboxItem pending = repository.insertPending(fact, created);
		assertThat(repository.compareAndSet(pending, pending.accept())).isTrue();
		UsageOutboxItem accepted = repository.findById(pending.id()).orElseThrow();
		Instant retryAt = created.plusSeconds(2);
		assertThat(repository.compareAndSet(accepted, accepted.retry(retryAt, "HTTP_503"))).isTrue();

		org.bson.Document raw = client.getDatabase("usage_outbox_test").getCollection("usage_outbox")
				.find(new org.bson.Document("_id", pending.id())).first();
		assertThat(raw).isNotNull();
		assertThat(raw.get("nextAttemptAt")).isInstanceOf(java.util.Date.class);
		java.util.Date stored = raw.getDate("nextAttemptAt");
		assertThat(stored.toInstant()).isEqualTo(retryAt);
		assertThat(stored.getTime()).isBetween(0L, 253402300799999L);
		assertThat(stored.toString()).doesNotContain("Invalid");

		assertThat(repository.findAcceptedReady(created.plusSeconds(1))).isEmpty();
		assertThat(repository.findAcceptedReady(retryAt)).extracting(UsageOutboxItem::id)
				.containsExactly(pending.id());
	}

	private static UsageFact fact(String recordId, long bytes) {
		return UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of(recordId), bytes,
				Instant.parse("2026-11-09T00:00:00Z"));
	}
}
