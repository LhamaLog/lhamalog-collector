package br.com.lhamalog.collector.usage.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageOutboxItemTest {

	private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

	@Test
	void appliesOnlyTheAllowedStateTransitions() {
		UsageOutboxItem pending = UsageOutboxItem.pending(fact(), NOW);

		assertThat(pending.accept().state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(pending.abandon().state()).isEqualTo(UsageOutboxState.ABANDONED);
		assertThat(pending.markAmbiguous().state()).isEqualTo(UsageOutboxState.AMBIGUOUS);
		assertThat(pending.accept().sync(NOW).state()).isEqualTo(UsageOutboxState.SYNCED);
		assertThatThrownBy(() -> pending.sync(NOW)).isInstanceOf(IllegalStateException.class);
		assertThat(pending.accept().suspend("HTTP_401").state()).isEqualTo(UsageOutboxState.SUSPENDED);
		assertThat(pending.accept().sync(NOW).expireAt()).isEqualTo(NOW.plus(UsageOutboxItem.RETENTION));
	}

	@Test
	void acquiresAnExpiredLeaseAndKeepsRetryMetadataOutsideTheImmutableFact() {
		UsageOutboxItem accepted = UsageOutboxItem.pending(fact(), NOW).accept();

		UsageOutboxItem leased = accepted.lease("worker-a", NOW, NOW.plusSeconds(30));
		UsageOutboxItem retry = leased.retry(NOW.plusSeconds(60), "HTTP_503");

		assertThat(leased.attemptCount()).isOne();
		assertThat(leased.leaseOwner()).isEqualTo("worker-a");
		assertThat(retry.state()).isEqualTo(UsageOutboxState.ACCEPTED);
		assertThat(retry.leaseOwner()).isNull();
		assertThat(retry.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(retry.fact()).isEqualTo(accepted.fact());
	}

	private static UsageFact fact() {
		return UsageFact.create("tenant-a", "product-a", UsageDomain.LOGS, List.of("record-1"), 1, NOW);
	}
}
