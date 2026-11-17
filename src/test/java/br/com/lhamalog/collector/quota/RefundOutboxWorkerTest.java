package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.logging.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RefundOutboxWorkerTest {
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void setsAndClearsCorrelationIdWhileDelivering() {
		AtomicReference<String> seenDuringRefund = new AtomicReference<>();
		InMemoryOutbox outbox = new InMemoryOutbox();
		outbox.enqueue("r-cid", "sink", NOW);
		new RefundOutboxWorker(outbox, new QuotaReservationClient() {
			@Override public ReservationResult reserve(ReservationRequest request) { throw new UnsupportedOperationException(); }
			@Override public void confirm(String reservationId) { }
			@Override public void release(String reservationId) { }
			@Override public int refund(String reservationId) {
				seenDuringRefund.set(CorrelationId.current());
				return 200;
			}
		}, properties(), Clock.fixed(NOW, ZoneOffset.UTC)).runOnce();

		assertThat(CorrelationId.isValid(seenDuringRefund.get())).isTrue();
		assertThat(CorrelationId.current()).isNull();
	}

	@Test
	void duplicateReservationIdEnqueuesOnlyOneRefund() {
		InMemoryOutbox outbox = new InMemoryOutbox();
		outbox.enqueue("r-1", "first", NOW);
		outbox.enqueue("r-1", "second", NOW.plusSeconds(1));
		assertThat(outbox.items).hasSize(1);
		assertThat(outbox.items.getFirst().reason()).isEqualTo("first");
	}

	@Test
	void retriesWithCeilingAndBsonSafeDate() {
		InMemoryOutbox outbox = new InMemoryOutbox();
		RefundOutboxItem item = new RefundOutboxItem("r-1", RefundOutboxState.PENDING, "sink", 50, NOW, NOW, NOW, null);
		RefundOutboxWorker worker = worker(outbox, 503);
		assertThat(worker.nextRetryAt(item, NOW)).isEqualTo(NOW.plusSeconds(2));
		assertThat(worker.nextRetryAt(item, RefundOutboxWorker.BSON_DATE_MAX)).isEqualTo(RefundOutboxWorker.BSON_DATE_MAX);
	}

	@Test
	void unauthorizedAndForbiddenSuspendWhileConflictIsTerminalDone() {
		for (int status : List.of(401, 403, 409)) {
			InMemoryOutbox outbox = new InMemoryOutbox();
			outbox.enqueue("r-" + status, "sink", NOW);
			worker(outbox, status).runOnce();
			assertThat(outbox.items.getFirst().state()).isEqualTo(status == 409 ? RefundOutboxState.DONE : RefundOutboxState.SUSPENDED);
		}
	}

	@Test
	void retryAdvancesAttemptAndObservability() {
		InMemoryOutbox outbox = new InMemoryOutbox();
		outbox.enqueue("r-503", "sink", NOW);
		io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		RefundOutboxWorker worker = new RefundOutboxWorker(outbox, client(503), properties(),
				Clock.fixed(NOW, ZoneOffset.UTC), new QuotaObservability(meters));
		worker.runOnce();
		assertThat(outbox.items.getFirst().attemptCount()).isEqualTo(1);
		assertThat(meters.get("lhamalog.quota.refund_retry").counter().count()).isEqualTo(1);
	}

	private static RefundOutboxWorker worker(InMemoryOutbox outbox, int status) {
		return new RefundOutboxWorker(outbox, client(status), properties(), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static QuotaProperties properties() {
		return new QuotaProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(250),
				Duration.ofSeconds(2), Duration.ofSeconds(300), 5, Duration.ofMinutes(30));
	}

	private static QuotaReservationClient client(int status) {
		return new QuotaReservationClient() {
			@Override public ReservationResult reserve(ReservationRequest request) { throw new UnsupportedOperationException(); }
			@Override public void confirm(String reservationId) { }
			@Override public void release(String reservationId) { }
			@Override public int refund(String reservationId) { return status; }
		};
	}

	private static final class InMemoryOutbox implements RefundOutboxRepository {
		private final List<RefundOutboxItem> items = new ArrayList<>();
		@Override public void enqueue(String id, String reason, Instant now) {
			if (items.stream().noneMatch(item -> item.reservationId().equals(id))) items.add(RefundOutboxItem.pending(id, reason, now));
		}
		@Override public List<RefundOutboxItem> ready(Instant now) {
			return items.stream().filter(item -> item.state() == RefundOutboxState.PENDING
					&& !item.nextAttemptAt().isAfter(now)).toList();
		}
		@Override public boolean replace(RefundOutboxItem expected, RefundOutboxItem replacement) {
			int index = items.indexOf(expected);
			if (index < 0) return false;
			items.set(index, replacement);
			return true;
		}
	}
}
