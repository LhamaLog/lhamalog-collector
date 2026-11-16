package br.com.lhamalog.collector.usage.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageFactTest {

	@Test
	void requiresTheVersionedUsageFactInvariants() {
		assertThatThrownBy(() -> new UsageFact("fact", "batch", "tenant", "product", UsageDomain.LOGS,
				List.of("record", "record"), 2, 0, Instant.parse("2026-11-09T00:00:00Z"), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageFact("fact", "batch", "tenant", "product", UsageDomain.LOGS,
				List.of("record"), 2, 0, Instant.parse("2026-11-09T00:00:00Z"), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageFact("fact", "batch", "tenant", "product", UsageDomain.LOGS,
				List.of("record"), 1, -1, Instant.parse("2026-11-09T00:00:00Z"), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UsageFact("fact", "batch", "tenant", "product", UsageDomain.LOGS,
				List.of("record"), 1, 0, Instant.parse("2026-11-09T00:00:00Z"), 2))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
