package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class QuotaObservabilityTest {
	@Test
	void exposesEveryRequiredCounterWithoutSensitiveTags() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		QuotaObservability metrics = new QuotaObservability(meters);
		metrics.authorized();
		metrics.unlimited();
		metrics.insufficient();
		metrics.noPlan();
		metrics.coreUnavailable();
		metrics.released();
		metrics.postDebitWriteFailure();
		metrics.refundEnqueued();
		metrics.refundSucceeded();
		metrics.refundRetry();
		metrics.refundSuspended();

		assertThat(meters.getMeters()).hasSize(11);
		assertThat(meters.get("lhamalog.quota.authorized").counter().count()).isEqualTo(1);
		assertThat(meters.get("lhamalog.quota.refund_suspended").counter().count()).isEqualTo(1);
		assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
	}
}
