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
	void mapsQuotaUnavailableTo503WithRetryAfter120() {
		OtlpHttpStatusMapper.HttpOutcome outcome = OtlpHttpStatusMapper.map(new IngestionDecision.QuotaUnavailable(),
				OtlpHttpStatusMapper.Signal.TRACES);

		assertThat(outcome.status()).isEqualTo(503);
		assertThat(outcome.headers()).containsEntry("Retry-After", "120");
		assertThat(((Status) outcome.body()).getCode()).isEqualTo(Code.UNAVAILABLE.getNumber());
	}
}
