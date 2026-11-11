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
		assertThat(rejected.item.state()).isEqualTo(UsageOutboxState.SUSPENDED);
		assertThat(rejected.item.lastErrorCode()).isEqualTo("HTTP_409");

		FakeOutbox unauthorized = new FakeOutbox(accepted());
		new UsageOutboxWorker(unauthorized, fact -> 401, properties(), CLOCK, "worker").runOnce();
		assertThat(unauthorized.item.state()).isEqualTo(UsageOutboxState.SUSPENDED);
		assertThat(unauthorized.item.lastErrorCode()).isEqualTo("HTTP_401");

		FakeOutbox forbidden = new FakeOutbox(accepted());
		new UsageOutboxWorker(forbidden, fact -> 403, properties(), CLOCK, "worker").runOnce();
		assertThat(forbidden.item.state()).isEqualTo(UsageOutboxState.SUSPENDED);

		FakeOutbox throttled = new FakeOutbox(accepted());
		new UsageOutboxWorker(throttled, fact -> 429, properties(), CLOCK, "worker").runOnce();
		assertThat(throttled.item.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(throttled.item.nextAttemptAt()).isAfter(NOW);
		assertThat(throttled.item.nextAttemptAt()).isBeforeOrEqualTo(NOW.plusSeconds(30));

		FakeOutbox serverError = new FakeOutbox(accepted());
		new UsageOutboxWorker(serverError, fact -> 503, properties(), CLOCK, "worker").runOnce();
		assertThat(serverError.item.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(serverError.item.nextAttemptAt()).isAfter(NOW);
	}

	@Test
	void capsBackoffSoNextAttemptStaysInsideBsonDateRange() {
		UsageOutboxItem base = accepted();
		UsageOutboxItem manyAttempts = new UsageOutboxItem(base.id(), base.fact(), base.state(), 10_000,
				base.nextAttemptAt(), null, null, null, base.createdAt(), null, base.acceptedEvidenceKey(), null);
		Instant next = UsageOutboxWorker.nextRetryAt(manyAttempts, NOW, properties());
		assertThat(next.toEpochMilli()).isBetween(0L, UsageOutboxWorker.BSON_DATE_MAX.toEpochMilli());
		assertThat(next).isAfter(NOW);
		assertThat(next).isBeforeOrEqualTo(NOW.plusSeconds(31));
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
			boolean leaseFree = item.leaseUntil() == null || !item.leaseUntil().isAfter(instant);
			return item.state() == UsageOutboxState.ACCEPTED && !item.nextAttemptAt().isAfter(instant) && leaseFree
					? List.of(item) : List.of();
		}
		@Override public boolean compareAndSet(String id, UsageOutboxState expected, UsageOutboxItem replacement) {
			if (item.id().equals(id) && item.state() == expected) {
				item = replacement;
				return true;
			}
			return false;
		}

		@Override public boolean compareAndSet(UsageOutboxItem expected, UsageOutboxItem replacement) {
			if (item.id().equals(expected.id()) && item.state() == expected.state()
					&& java.util.Objects.equals(item.leaseOwner(), expected.leaseOwner())
					&& java.util.Objects.equals(item.leaseUntil(), expected.leaseUntil())) {
				item = replacement;
				return true;
			}
			return false;
		}
	}
}
