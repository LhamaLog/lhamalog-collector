package br.com.lhamalog.collector.usage.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class UsageReconcilerIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

	@Container static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));
	@Container static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.2.10"))
		.withExposedPorts(6379);

	private static MongoClient client;
	private static MongoUsageOutboxRepository outbox;
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate strings;

	@BeforeAll
	static void setUp() {
		client = MongoClients.create(mongo.getReplicaSetUrl());
		outbox = new MongoUsageOutboxRepository(new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "usage_reconcile")));
		connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
		connection.afterPropertiesSet();
		strings = new StringRedisTemplate(connection);
		strings.afterPropertiesSet();
	}

	@AfterAll
	static void tearDown() {
		connection.destroy();
		client.close();
	}

	@Test
	void reconcilesAbsentDoneAndClaimedEvidenceAndFencesTheAbsentKeyForSevenDays() {
		UsageOutboxItem absent = pending("absent");
		UsageOutboxItem done = pending("done");
		UsageOutboxItem claimed = pending("claimed");
		strings.opsForValue().set(done.acceptedEvidenceKey(), "DONE:logs:1-0");
		strings.opsForValue().set(claimed.acceptedEvidenceKey(), "CLAIMED:dispatcher");

		new UsageAcceptanceReconciler(outbox, evidence(), Clock.fixed(NOW, ZoneOffset.UTC))
			.reconcilePendingBefore(NOW);

		assertThat(outbox.findById(absent.id()).orElseThrow().state()).isEqualTo(UsageOutboxState.ABANDONED);
		assertThat(outbox.findById(done.id()).orElseThrow().state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(outbox.findById(claimed.id()).orElseThrow().state()).isEqualTo(UsageOutboxState.AMBIGUOUS);
		assertThat(strings.opsForValue().get(absent.acceptedEvidenceKey())).isEqualTo("ABANDONED");
		assertThat(strings.getExpire(absent.acceptedEvidenceKey())).isBetween(604_790L, 604_800L);
	}

	private static UsageAcceptanceEvidence evidence() {
		return new UsageAcceptanceEvidence() {
			@Override public String read(String key) { return strings.opsForValue().get(key); }
			@Override public String resolvePending(String key) {
				strings.opsForValue().setIfAbsent(key, "ABANDONED", java.time.Duration.ofDays(7));
				return strings.opsForValue().get(key);
			}
		};
	}

	private static UsageOutboxItem pending(String suffix) {
		UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-" + suffix), 1, NOW);
		return outbox.insertPending(fact, NOW.minusSeconds(1));
	}
}
