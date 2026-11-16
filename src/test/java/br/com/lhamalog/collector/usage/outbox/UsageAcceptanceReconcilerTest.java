package br.com.lhamalog.collector.usage.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.logging.CorrelationId;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class UsageAcceptanceReconcilerTest {

	private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void setsAndClearsCorrelationIdWhileReconciling() {
		AtomicReference<String> seenDuringResolve = new AtomicReference<>();
		FakeOutbox outbox = new FakeOutbox(UsageOutboxItem.pending(fact("cid"), NOW));
		new UsageAcceptanceReconciler(outbox, key -> {
			seenDuringResolve.set(CorrelationId.current());
			return "DONE:logs:1-0";
		}, Clock.fixed(NOW, ZoneOffset.UTC)).reconcilePendingBefore(NOW);

		assertThat(CorrelationId.isValid(seenDuringResolve.get())).isTrue();
		assertThat(CorrelationId.current()).isNull();
	}

	@Test
	void convertsOnlyRedisEvidenceToTerminalOrAcceptedMongoStates() {
		for (Evidence expected : Evidence.values()) {
			FakeOutbox outbox = new FakeOutbox(UsageOutboxItem.pending(fact(expected.name()), NOW));
			new UsageAcceptanceReconciler(outbox, key -> expected.value,
					Clock.fixed(NOW, ZoneOffset.UTC)).reconcilePendingBefore(NOW);
			assertThat(outbox.item.state()).isEqualTo(expected.state);
		}
	}

	private static UsageFact fact(String suffix) {
		return UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record-" + suffix), 1, NOW);
	}

	private enum Evidence {
		MISSING(null, UsageOutboxState.ABANDONED),
		DONE("DONE:logs:1-0", UsageOutboxState.ACCEPTED),
		CLAIMED("CLAIMED:worker", UsageOutboxState.AMBIGUOUS),
		ABANDONED("ABANDONED", UsageOutboxState.ABANDONED);

		private final String value;
		private final UsageOutboxState state;

		Evidence(String value, UsageOutboxState state) {
			this.value = value;
			this.state = state;
		}
	}

	private static final class FakeOutbox implements UsageOutboxRepository {
		private UsageOutboxItem item;
		private FakeOutbox(UsageOutboxItem item) { this.item = item; }
		@Override public UsageOutboxItem insertPending(UsageFact fact, Instant now) { return item; }
		@Override public Optional<UsageOutboxItem> findById(String id) { return Optional.of(item); }
		@Override public List<UsageOutboxItem> findPendingBefore(Instant deadline) { return List.of(item); }
		@Override public List<UsageOutboxItem> findAcceptedReady(Instant instant) { return List.of(); }
		@Override public boolean compareAndSet(String id, UsageOutboxState expected, UsageOutboxItem replacement) {
			if (item.state() != expected) return false;
			item = replacement;
			return true;
		}
	}
}
