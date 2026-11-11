package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.config.UsageProperties;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.outbox.MongoUsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
class UsageOutboxRecoveryIntegrationTest {

	private static final Instant CREATED = Instant.parse("2026-11-09T00:00:00Z");

	@Container static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static MongoClient client;
	private static MongoUsageOutboxRepository outbox;

	@BeforeAll
	static void setUp() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		outbox = new MongoUsageOutboxRepository(new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "usage_recovery")));
	}

	@AfterAll
	static void tearDown() {
		client.close();
	}

	@Test
	void restartRecoversAnExpiredLeaseAndSendsTheSameUsageFactId() {
		UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.TRACES, List.of("record-recovery"), 42, CREATED);
		UsageOutboxItem pending = outbox.insertPending(fact, CREATED);
		UsageOutboxItem accepted = pending.accept();
		assertThat(outbox.compareAndSet(pending.id(), UsageOutboxState.PENDING, accepted)).isTrue();
		UsageOutboxItem expiredLease = accepted.lease("crashed-worker", CREATED, CREATED.plusSeconds(5));
		assertThat(outbox.compareAndSet(accepted.id(), UsageOutboxState.ACCEPTED, expiredLease)).isTrue();

		List<String> submittedIds = new ArrayList<>();
		Instant restartedAt = CREATED.plusSeconds(6);
		new UsageOutboxWorker(outbox, sent -> {
			submittedIds.add(sent.usageFactId());
			return 201;
		}, properties(), Clock.fixed(restartedAt, ZoneOffset.UTC)).runOnce();

		assertThat(submittedIds).containsExactly(fact.usageFactId());
		assertThat(outbox.findById(fact.usageFactId()).orElseThrow().state()).isEqualTo(UsageOutboxState.SYNCED);
	}

	@Test
	void onlyOneOfTwoConcurrentWorkersSendsTheFact() throws Exception {
		UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-concurrent"), 7,
				CREATED);
		UsageOutboxItem pending = outbox.insertPending(fact, CREATED);
		assertThat(outbox.compareAndSet(pending, pending.accept())).isTrue();

		java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
		UsageFactsClient slow = sent -> {
			sends.incrementAndGet();
			Thread.sleep(150);
			return 201;
		};
		UsageOutboxWorker first = new UsageOutboxWorker(outbox, slow, properties(),
				Clock.fixed(CREATED.plusSeconds(1), ZoneOffset.UTC), "worker-a");
		UsageOutboxWorker second = new UsageOutboxWorker(outbox, slow, properties(),
				Clock.fixed(CREATED.plusSeconds(1), ZoneOffset.UTC), "worker-b");
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			pool.invokeAll(List.of(ExecutorsCallable(first), ExecutorsCallable(second)));
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(sends.get()).isEqualTo(1);
		assertThat(outbox.findById(fact.usageFactId()).orElseThrow().state()).isEqualTo(UsageOutboxState.SYNCED);
	}

	private static java.util.concurrent.Callable<Void> ExecutorsCallable(UsageOutboxWorker worker) {
		return () -> {
			worker.runOnce();
			return null;
		};
	}

	private static UsageProperties properties() {
		return new UsageProperties(true, new UsageProperties.Core("http://core.invalid"), Duration.ofSeconds(5),
				Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofMillis(1), Duration.ofSeconds(1));
	}
}
