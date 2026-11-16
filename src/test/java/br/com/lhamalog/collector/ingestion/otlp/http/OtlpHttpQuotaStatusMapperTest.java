package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import com.google.rpc.Code;
import com.google.rpc.Status;
import org.junit.jupiter.api.Test;

class OtlpHttpQuotaStatusMapperTest {

	@Test
	void mapsQuotaExceededTo429WithRetryAfter120() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.QuotaExceeded(),
				OtlpHttpStatusMapper.Signal.LOGS);

		assertThat(outcome.status()).isEqualTo(429);
		assertThat(outcome.headers()).containsEntry("Retry-After", "120");
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED.getNumber());
	}

	@Test
	void mapsQuotaUnavailableTo503WithRetryAfter300() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.QuotaUnavailable(300),
				OtlpHttpStatusMapper.Signal.TRACES);

		assertThat(outcome.status()).isEqualTo(503);
		assertThat(outcome.headers()).containsEntry("Retry-After", "300");
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.UNAVAILABLE.getNumber());
	}

	@Test
	void mapsRateLimitedTo429WithConfiguredRetryAfter5() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.RateLimited(5),
				OtlpHttpStatusMapper.Signal.LOGS);

		assertThat(outcome.status()).isEqualTo(429);
		assertThat(outcome.headers()).containsEntry("Retry-After", "5");
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED.getNumber());
	}

	@Test
	void mapsRateBlockedTo429WithRetryAfter300() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.RateLimited(300),
				OtlpHttpStatusMapper.Signal.LOGS);

		assertThat(outcome.status()).isEqualTo(429);
		assertThat(outcome.headers()).containsEntry("Retry-After", "300");
	}

	@Test
	void mapsQuotaUnavailableExhaustedTo500WithoutRetryAfter() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(
				new IngestionDecision.QuotaUnavailableExhausted(), OtlpHttpStatusMapper.Signal.LOGS);

		assertThat(outcome.status()).isEqualTo(500);
		assertThat(outcome.headers()).isEmpty();
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.INTERNAL.getNumber());
		assertThat(((Status) outcome.body()).getMessage()).contains("retry budget exhausted");
	}

	@Test
	void mapsBatchTooLargeTo413() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.BatchTooLarge(),
				OtlpHttpStatusMapper.Signal.TRACES);

		assertThat(outcome.status()).isEqualTo(413);
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED.getNumber());
	}

	@Test
	void mapsSuspendedTo403() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.Suspended(),
				OtlpHttpStatusMapper.Signal.LOGS);

		assertThat(outcome.status()).isEqualTo(403);
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.PERMISSION_DENIED.getNumber());
	}
}
