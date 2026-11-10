package br.com.lhamalog.collector.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageFactIdTest {

	@Test
	void derivesStableBatchAndFactIdsFromTheOrderedAcceptedRecordIds() {
		UsageFact original = UsageFact.create("tenant-a", "product-a", UsageDomain.LOGS,
				List.of("record-1", "record-2"), 42, Instant.parse("2026-11-09T00:00:00Z"));
		UsageFact replay = UsageFact.create("tenant-a", "product-a", UsageDomain.LOGS,
				List.of("record-1", "record-2"), 42, Instant.parse("2026-11-09T00:01:00Z"));
		UsageFact differentOrder = UsageFact.create("tenant-a", "product-a", UsageDomain.LOGS,
				List.of("record-2", "record-1"), 42, Instant.parse("2026-11-09T00:00:00Z"));

		assertThat(replay.batchId()).isEqualTo(original.batchId());
		assertThat(replay.usageFactId()).isEqualTo(original.usageFactId());
		assertThat(differentOrder.batchId()).isNotEqualTo(original.batchId());
		assertThat(differentOrder.usageFactId()).isNotEqualTo(original.usageFactId());
	}
}
