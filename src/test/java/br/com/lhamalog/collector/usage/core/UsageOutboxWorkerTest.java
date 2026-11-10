package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.config.UsageProperties;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UsageOutboxWorkerTest {

	private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void marksAcceptedFactSyncedOnlyAfterA2xxResponse() {
		FakeOutbox outbox = new FakeOutbox(accepted());
		new UsageOutboxWorker(outbox, fact -> 201, properties(), CLOCK, "worker").runOnce();

		assertThat(outbox.item.state()).isEqualTo(UsageOutboxState.SYNCED);
		assertThat(outbox.item.attemptCount()).isOne();
	}

	@Test
	void retriesNetworkAndServerFailuresButSuspendsOther4xxResponses() {
		FakeOutbox unavailable = new FakeOutbox(accepted());
		new UsageOutboxWorker(unavailable, fact -> {
			throw new IOException("offline");
		}, properties(), CLOCK, "worker").runOnce();
		assertThat(unavailable.item.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(unavailable.item.lastErrorCode()).isEqualTo("NETWORK");
		assertThat(unavailable.item.nextAttemptAt()).isAfter(NOW);

		FakeOutbox rejected = new FakeOutbox(accepted());
		new UsageOutboxWorker(rejected, fact -> 409, properties(), CLOCK, "worker").runOnce();
		assertThat(rejected.item.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(rejected.item.lastErrorCode()).isEqualTo("HTTP_409");
		assertThat(rejected.item.nextAttemptAt()).isEqualTo(Instant.ofEpochMilli(Long.MAX_VALUE));
	}

	private static UsageOutboxItem accepted() {
		return UsageOutboxItem.pending(UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record"), 1, NOW),
				NOW).accept();
	}

	private static UsageProperties properties() {
		return new UsageProperties(true, new UsageProperties.Core("http://localhost:8081"), java.time.Duration.ofSeconds(30),
				java.time.Duration.ofSeconds(10), java.time.Duration.ofMinutes(2), java.time.Duration.ofDays(7),
				java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(30));
	}

	private static final class FakeOutbox implements UsageOutboxRepository {
		private UsageOutboxItem item;

		private FakeOutbox(UsageOutboxItem item) {
			this.item = item;
		}

		@Override public UsageOutboxItem insertPending(UsageFact fact, Instant now) { return item; }
		@Override public Optional<UsageOutboxItem> findById(String id) { return Optional.of(item); }
		@Override public List<UsageOutboxItem> findPendingBefore(Instant instant) { return List.of(); }
		@Override public List<UsageOutboxItem> findAcceptedReady(Instant instant) {
			return item.state() == UsageOutboxState.ACCEPTED && !item.nextAttemptAt().isAfter(instant) ? List.of(item) : List.of();
		}
		@Override public boolean compareAndSet(String id, UsageOutboxState expected, UsageOutboxItem replacement) {
			if (item.id().equals(id) && item.state() == expected) {
				item = replacement;
				return true;
			}
			return false;
		}
	}
}
