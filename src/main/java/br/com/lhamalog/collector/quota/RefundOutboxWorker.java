package br.com.lhamalog.collector.quota;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends refunds asynchronously; only a successful Core transition makes an item terminal. */
public final class RefundOutboxWorker {
	private static final Logger log = LoggerFactory.getLogger(RefundOutboxWorker.class);
	static final Instant BSON_DATE_MAX = Instant.ofEpochMilli(253402300799999L);

	private final RefundOutboxRepository outbox;
	private final QuotaReservationClient client;
	private final QuotaProperties properties;
	private final Clock clock;
	private final QuotaObservability observability;

	public RefundOutboxWorker(RefundOutboxRepository outbox, QuotaReservationClient client, QuotaProperties properties,
			Clock clock) {
		this(outbox, client, properties, clock, QuotaObservability.NOOP);
	}

	public RefundOutboxWorker(RefundOutboxRepository outbox, QuotaReservationClient client, QuotaProperties properties,
			Clock clock, QuotaObservability observability) {
		this.outbox = outbox;
		this.client = client;
		this.properties = properties;
		this.clock = clock;
		this.observability = observability;
	}

	public void runOnce() {
		Instant now = clock.instant();
		for (RefundOutboxItem item : outbox.ready(now)) {
			deliver(item, now);
		}
	}

	private void deliver(RefundOutboxItem item, Instant now) {
		try {
			int status = client.refund(item.reservationId());
			if ((status >= 200 && status < 300) || status == 409) {
				outbox.replace(item, item.done(now));
				observability.refundSucceeded();
			}
			else if (status == 401 || status == 403) {
				outbox.replace(item, item.suspended(now, "HTTP_" + status));
				observability.refundSuspended();
			}
			else {
				retry(item, now, "HTTP_" + status);
			}
		}
		catch (RuntimeException exception) {
			retry(item, now, "NETWORK");
		}
	}

	private void retry(RefundOutboxItem item, Instant now, String code) {
		outbox.replace(item, item.retry(now, nextRetryAt(item, now), code));
		observability.refundRetry();
		log.warn("Refund for reservation {} deferred ({})", item.reservationId(), code);
	}

	Instant nextRetryAt(RefundOutboxItem item, Instant now) {
		long base = Math.max(1, properties.refundBackoffInitial().toMillis());
		long ceiling = Math.max(base, properties.refundBackoffCeiling().toMillis());
		int shift = Math.min(20, Math.max(0, item.attemptCount()));
		long delay = base;
		for (int i = 0; i < shift && delay <= ceiling / 2; i++) delay *= 2;
		delay = Math.min(delay, ceiling);
		try {
			Instant next = now.plusMillis(delay);
			return next.isAfter(BSON_DATE_MAX) ? BSON_DATE_MAX : next;
		}
		catch (ArithmeticException exception) {
			return BSON_DATE_MAX;
		}
	}
}
