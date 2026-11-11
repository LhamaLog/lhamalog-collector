package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;

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
class MongoRefundOutboxRepositoryTest {
	@Container static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));
	private static MongoClient client;
	private static MongoRefundOutboxRepository repository;

	@BeforeAll
	static void start() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		repository = new MongoRefundOutboxRepository(
				new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "refund_outbox_test")));
	}

	@AfterAll static void stop() { client.close(); }

	@Test
	void duplicateReservationIdProducesOneDurableItem() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		repository.enqueue("duplicate", "sink", now);
		repository.enqueue("duplicate", "retry", now.plusSeconds(1));
		assertThat(repository.ready(now)).extracting(RefundOutboxItem::reservationId).containsExactly("duplicate");
	}

	@Test
	void nextAttemptIsStoredAsBsonDateAndReadyQueryUsesIt() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		repository.enqueue("date", "sink", now);
		RefundOutboxItem pending = repository.ready(now).stream()
				.filter(item -> item.reservationId().equals("date")).findFirst().orElseThrow();
		Instant next = now.plusSeconds(3);
		assertThat(repository.replace(pending, pending.retry(now, next, "HTTP_503"))).isTrue();
		org.bson.Document raw = client.getDatabase("refund_outbox_test").getCollection(MongoRefundOutboxRepository.COLLECTION)
				.find(new org.bson.Document("_id", "date")).first();
		assertThat(raw.get("nextAttemptAt")).isInstanceOf(java.util.Date.class);
		assertThat(raw.getDate("nextAttemptAt").toInstant()).isEqualTo(next);
		assertThat(repository.ready(now.plusSeconds(2))).extracting(RefundOutboxItem::reservationId)
				.doesNotContain("date");
		assertThat(repository.ready(next)).extracting(RefundOutboxItem::reservationId).contains("date");
	}
}
