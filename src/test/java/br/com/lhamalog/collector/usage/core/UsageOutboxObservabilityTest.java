package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageOutboxObservabilityTest {

	@Test
	void recordsOnlyOperationalStateAttemptsAgeAndEvidenceRetention() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		UsageOutboxObservability observability = new UsageOutboxObservability(meters);
		Instant created = Instant.parse("2026-11-09T00:00:00Z");
		UsageOutboxItem item = UsageOutboxItem.pending(
				UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record"), 1, created), created);

		observability.transition(UsageOutboxState.PENDING);
		observability.retry(item.accept().retry(created.plusSeconds(1), "NETWORK"));
		observability.pendingAge(item, created.plusSeconds(61), Duration.ofSeconds(60));
		observability.suspended(item.accept());
		observability.ambiguous();
		observability.evidenceExpiryConfigured(Duration.ofDays(7));

		assertThat(meters.get("lhamalog.usage.outbox.transitions").tag("state", "PENDING").counter().count()).isOne();
		assertThat(meters.get("lhamalog.usage.outbox.retries").counter().count()).isOne();
		assertThat(meters.get("lhamalog.usage.outbox.suspended").counter().count()).isOne();
		assertThat(meters.get("lhamalog.usage.outbox.pending.over_grace").counter().count()).isOne();
		assertThat(meters.get("lhamalog.usage.outbox.ambiguous").counter().count()).isOne();
		assertThat(meters.get("lhamalog.usage.outbox.pending.age.seconds").gauge().value()).isEqualTo(61);
		assertThat(meters.get("lhamalog.usage.outbox.evidence.ttl.seconds").gauge().value()).isEqualTo(604800);
	}
}
