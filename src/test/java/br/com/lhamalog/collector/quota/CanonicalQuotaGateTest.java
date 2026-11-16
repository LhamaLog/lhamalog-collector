package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.QuotaGate;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CanonicalQuotaGateTest {

	@Test
	void mapsCoreOutcomesAndEnqueuesRefundWithDeterministicReservationId() {
		AtomicReference<QuotaReservationClient.ReservationRequest> seen = new AtomicReference<>();
		List<String> refunds = new ArrayList<>();
		QuotaReservationClient client = new QuotaReservationClient() {
			@Override
			public ReservationOutcome reserve(ReservationRequest request) {
				seen.set(request);
				return ReservationOutcome.AUTHORIZED;
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}

			@Override
			public int refund(String reservationId) {
				return 204;
			}
		};
		RefundOutboxRepository outbox = new RefundOutboxRepository() {
			@Override
			public void enqueue(String reservationId, String reason, Instant now) {
				refunds.add(reservationId + ":" + reason);
			}

			@Override
			public List<RefundOutboxItem> ready(Instant now) {
				return List.of();
			}

			@Override
			public boolean replace(RefundOutboxItem expected, RefundOutboxItem replacement) {
				return true;
			}
		};
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> gate = new CanonicalQuotaGate<>(client, outbox,
				new QuotaObservability(meters));
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.getDefaultInstance())
						.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.getDefaultInstance())))
				.build();
		List<Scoped<LogRecord>> accepted = List.of(new Scoped<>(Resource.getDefaultInstance(),
				io.opentelemetry.proto.common.v1.InstrumentationScope.getDefaultInstance(), LogRecord.getDefaultInstance()));

		QuotaGate.Reservation first = gate.reserve("tenant", "product", request, accepted);
		QuotaGate.Reservation second = gate.reserve("tenant", "product", request, accepted);
		gate.refund(first.reservationId(), "SINK_UNAVAILABLE");

		assertThat(first.outcome()).isEqualTo(QuotaGate.Reservation.Outcome.AUTHORIZED);
		assertThat(first.reservationId()).isEqualTo(second.reservationId()).startsWith("quota:");
		assertThat(seen.get().domain()).isEqualTo(UsageDomain.LOGS);
		assertThat(seen.get().requestedBytes()).isPositive();
		assertThat(refunds).containsExactly(first.reservationId() + ":SINK_UNAVAILABLE");
		assertThat(meters.counter("lhamalog.quota.authorized").count()).isEqualTo(2);
		assertThat(meters.counter("lhamalog.quota.refund_enqueued").count()).isEqualTo(1);
	}

	@Test
	void mapsInsufficientBalanceAndNoPlanWithoutRefund() {
		CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> insufficient = gateReturning(
				QuotaReservationClient.ReservationOutcome.INSUFFICIENT_BALANCE);
		CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> noPlan = gateReturning(
				QuotaReservationClient.ReservationOutcome.NO_PLAN);
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
				.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.getDefaultInstance())
						.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.getDefaultInstance())))
				.build();
		List<Scoped<LogRecord>> accepted = List.of(new Scoped<>(Resource.getDefaultInstance(),
				io.opentelemetry.proto.common.v1.InstrumentationScope.getDefaultInstance(), LogRecord.getDefaultInstance()));

		assertThat(insufficient.reserve("tenant", "product", request, accepted).outcome())
				.isEqualTo(QuotaGate.Reservation.Outcome.INSUFFICIENT_BALANCE);
		assertThat(noPlan.reserve("tenant", "product", request, accepted).outcome())
				.isEqualTo(QuotaGate.Reservation.Outcome.NO_PLAN);
	}

	private static CanonicalQuotaGate<ExportLogsServiceRequest, Scoped<LogRecord>> gateReturning(
			QuotaReservationClient.ReservationOutcome outcome) {
		return new CanonicalQuotaGate<>(new QuotaReservationClient() {
			@Override
			public ReservationOutcome reserve(ReservationRequest request) {
				return outcome;
			}

			@Override
			public void confirm(String reservationId) {
			}

			@Override
			public void release(String reservationId) {
			}

			@Override
			public int refund(String reservationId) {
				return 204;
			}
		});
	}
}
