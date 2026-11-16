package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import org.junit.jupiter.api.Test;

/** T006 — new ingestion outcomes for rate, batch size and suspension. */
class IngestionDecisionOutcomesTest {

	@Test
	void rateLimitedCarriesRetryAfterSeconds() {
		IngestionDecision decision = new IngestionDecision.RateLimited(5);
		assertThat(decision).isInstanceOf(IngestionDecision.RateLimited.class);
		assertThat(((IngestionDecision.RateLimited) decision).retryAfterSeconds()).isEqualTo(5);
	}

	@Test
	void quotaUnavailableExhaustedOutcomeExists() {
		assertThat(new IngestionDecision.QuotaUnavailableExhausted())
				.isInstanceOf(IngestionDecision.QuotaUnavailableExhausted.class);
	}

	@Test
	void batchTooLargeIsDistinctFromTransportTooLarge() {
		assertThat(new IngestionDecision.BatchTooLarge()).isInstanceOf(IngestionDecision.BatchTooLarge.class);
		assertThat(IngestionDecision.Reason.BATCH_TOO_LARGE).isNotEqualTo(IngestionDecision.Reason.TOO_LARGE);
	}

	@Test
	void suspendedOutcomeExistsForLaterCoreWiring() {
		assertThat(new IngestionDecision.Suspended()).isInstanceOf(IngestionDecision.Suspended.class);
		assertThat(QuotaGate.Reservation.Outcome.SUSPENDED.name()).isEqualTo("SUSPENDED");
	}
}
