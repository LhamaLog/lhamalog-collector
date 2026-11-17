package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IngestionObservabilityMetersTest {

	@Test
	void acceptedIncrementsRecordsAndBytesWithSignalAndProtocol() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability obs = new IngestionObservability(meters);

		obs.accepted("logs", "http", 3, 128);

		assertThat(meters.find("lhamalog.ingestion.accepted.records")
				.tag("signal", "logs").tag("protocol", "http").counter().count()).isEqualTo(3.0d);
		assertThat(meters.find("lhamalog.ingestion.accepted.bytes")
				.tag("signal", "logs").tag("protocol", "http").counter().count()).isEqualTo(128.0d);
	}

	@Test
	void acceptedSkipsZeroRecords() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		new IngestionObservability(meters).accepted("logs", "http", 0, 10);
		assertThat(meters.find("lhamalog.ingestion.accepted.records").counter()).isNull();
	}

	@Test
	void latencyRecordsHistogramWithOutcome() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		IngestionObservability obs = new IngestionObservability(meters);

		obs.latency("traces", "grpc", IngestionObservability.OUTCOME_REFUSED, Duration.ofMillis(25));

		assertThat(meters.find("lhamalog.ingestion.latency")
				.tag("signal", "traces")
				.tag("protocol", "grpc")
				.tag("outcome", "refused")
				.timer().count()).isEqualTo(1L);
	}

	@Test
	void outcomeMapping() {
		assertThat(IngestionObservability.outcomeOf(new IngestionDecision.Accepted(1)))
				.isEqualTo("accepted");
		assertThat(IngestionObservability.outcomeOf(new IngestionDecision.Unauthorized()))
				.isEqualTo("refused");
		assertThat(IngestionObservability.outcomeOf(new IngestionDecision.DestinationUnavailable(5)))
				.isEqualTo("failed");
		assertThat(IngestionObservability.outcomeOf(new IngestionDecision.PartiallyAccepted(2, 1, "x")))
				.isEqualTo("partial");
	}
}
