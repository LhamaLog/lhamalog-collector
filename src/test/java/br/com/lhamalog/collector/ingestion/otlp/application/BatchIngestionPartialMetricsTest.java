package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.ratelimit.TenantRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** LLT-187 review B3: OpenObserve partial acceptance feeds accepted.records/.bytes with the stored share. */
class BatchIngestionPartialMetricsTest {

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final IngestionControls controls = new IngestionControls(TenantRateLimiter.allowAll(), 64 * 1024, 300, 5,
			300, CoreUnavailableAttemptTracker.alwaysRetryable(300), new IngestionObservability(meters));

	@AfterEach
	void clearProtocol() {
		IngestionProtocol.clear();
	}

	@Test
	void storedRecordsAndProportionalBytesAreCounted() {
		IngestionProtocol.set(IngestionProtocol.HTTP);

		BatchIngestion.recordPartialAcceptance(controls, "logs", 2, 3, 300L);

		assertThat(counter("lhamalog.ingestion.accepted.records")).isEqualTo(2.0d);
		assertThat(counter("lhamalog.ingestion.accepted.bytes")).isEqualTo(200.0d);
	}

	@Test
	void bytesRoundDownAndStoredCountIsCappedAtDelivered() {
		IngestionProtocol.set(IngestionProtocol.HTTP);

		BatchIngestion.recordPartialAcceptance(controls, "logs", 1, 3, 100L);
		BatchIngestion.recordPartialAcceptance(controls, "logs", 5, 2, 50L);

		assertThat(counter("lhamalog.ingestion.accepted.records")).isEqualTo(3.0d);
		assertThat(counter("lhamalog.ingestion.accepted.bytes")).isEqualTo(83.0d);
	}

	@Test
	void nothingStoredRecordsNothing() {
		BatchIngestion.recordPartialAcceptance(controls, "logs", 0, 3, 300L);

		assertThat(meters.find("lhamalog.ingestion.accepted.records").counter()).satisfiesAnyOf(
				c -> assertThat(c).isNull(), c -> assertThat(c.count()).isZero());
	}

	private double counter(String name) {
		return meters.find(name).tag("signal", "logs").tag("protocol", "http").counter().count();
	}
}
