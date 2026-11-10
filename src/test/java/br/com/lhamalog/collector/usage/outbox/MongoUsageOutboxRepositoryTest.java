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

	private static UsageFact fact(String recordId, long bytes) {
		return UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of(recordId), bytes,
				Instant.parse("2026-11-09T00:00:00Z"));
	}
}
