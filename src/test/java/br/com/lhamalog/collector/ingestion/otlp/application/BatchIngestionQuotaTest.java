package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.BatchAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchIngestionQuotaTest {

	@Test
	void releasesAuthorizedReservationWhenConcurrentSanitizationRejects() {
		AtomicInteger releases = new AtomicInteger();
		QuotaGate<String, String> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenant, String product, String request, List<String> accepted) {
				return new Reservation(Reservation.Outcome.AUTHORIZED, "reservation-1");
			}
			@Override public void confirm(String reservationId) {
			}
			@Override public void release(String reservationId) {
				releases.incrementAndGet();
			}
		};

		IngestionDecision result = BatchIngestion.run(token -> new TokenValidationResult.Valid("tenant", "product"),
				"token", () -> "body", ignored -> new BatchAssessment<>(List.of("record"), 0),
				(tenant, product, request, records) -> {
					throw new AssertionError("sink must not run");
				}, "records", "invalid", quota,
				(request, accepted) -> { throw new BatchSanitizer.RejectedException("reject"); });

		assertThat(result).isInstanceOf(IngestionDecision.InvalidPayload.class);
		assertThat(releases).hasValue(1);
	}

	@Test
	void startsSanitizationConcurrentlyWithReservation() {
		CountDownLatch sanitizerStarted = new CountDownLatch(1);
		AtomicInteger reserveSawSanitizer = new AtomicInteger();
		QuotaGate<String, String> quota = new AuthorizedGate() {
			@Override public Reservation reserve(String tenant, String product, String request, List<String> accepted) {
				try {
					if (sanitizerStarted.await(1, TimeUnit.SECONDS)) reserveSawSanitizer.incrementAndGet();
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return new Reservation(Reservation.Outcome.AUTHORIZED, "reservation-1");
			}
		};

		IngestionDecision result = run(quota, (request, accepted) -> {
			sanitizerStarted.countDown();
			return accepted;
		}, (tenant, product, request, records) -> { });

		assertThat(result).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(reserveSawSanitizer).hasValue(1);
	}

	@Test
	void confirmsBeforeSinkAndRefundsARefusalOnlyAfterConfirm() {
		List<String> calls = Collections.synchronizedList(new java.util.ArrayList<>());
		QuotaGate<String, String> quota = new AuthorizedGate() {
			@Override public void confirm(String id) { calls.add("confirm"); }
			@Override public void refund(String id, String reason) { calls.add("refund"); }
		};
		IngestionDecision result = run(quota, BatchSanitizer.identity(), (tenant, product, request, records) -> {
			calls.add("sink");
			throw new br.com.lhamalog.collector.ingestion.otlp.sink.SinkRejectedException(1, 0, "refused");
		});

		assertThat(result).isInstanceOf(IngestionDecision.InvalidPayload.class);
		assertThat(calls).containsExactly("confirm", "sink", "refund");
	}

	@Test
	void refusesWholeBatchWithoutDeliveryOnInsufficientBalance() {
		AtomicInteger delivered = new AtomicInteger();
		QuotaGate<String, String> quota = new QuotaGate<>() {
			@Override
			public Reservation reserve(String tenant, String product, String request, List<String> accepted) {
				return new Reservation(Reservation.Outcome.INSUFFICIENT_BALANCE, null);
			}
			@Override public void confirm(String reservationId) { throw new AssertionError("confirm"); }
			@Override public void release(String reservationId) { throw new AssertionError("release"); }
		};

		IngestionDecision result = run(quota, BatchSanitizer.identity(),
				(tenant, product, request, records) -> delivered.incrementAndGet());

		assertThat(result).isEqualTo(new IngestionDecision.QuotaExceeded());
		assertThat(delivered).hasValue(0);
	}

	@Test
	void returnsRetryAfter120WhenPostConfirmSinkFails() {
		IngestionDecision result = run(new AuthorizedGate(), BatchSanitizer.identity(),
				(tenant, product, request, records) -> {
					throw new br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException("down");
				});

		assertThat(result).isEqualTo(new IngestionDecision.DestinationUnavailable(120));
	}

	private static class AuthorizedGate implements QuotaGate<String, String> {
		@Override public Reservation reserve(String tenant, String product, String request, List<String> accepted) {
			return new Reservation(Reservation.Outcome.AUTHORIZED, "reservation-1");
		}
		@Override public void confirm(String reservationId) { }
		@Override public void release(String reservationId) { }
	}

	private static IngestionDecision run(QuotaGate<String, String> quota, BatchSanitizer<String, String> sanitizer,
			BatchIngestion.Delivery<String, String> sink) {
		return BatchIngestion.run(token -> new TokenValidationResult.Valid("tenant", "product"), "token", () -> "body",
				ignored -> new BatchAssessment<>(List.of("record"), 0), sink, "records", "invalid", quota, sanitizer);
	}
}
