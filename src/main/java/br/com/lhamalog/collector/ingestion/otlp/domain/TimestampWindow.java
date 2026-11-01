// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Accepted range of a record timestamp, relative to the instant the batch is assessed (T040, FR-019).
 *
 * <p>OpenObserve refuses data older than its ingestion window ({@code ZO_INGEST_ALLOWED_UPTO}, 5 hours by
 * default) and answers that refusal with a 5xx, which an OTLP exporter retries forever. Rejecting those
 * records here turns them into a non retryable, per record rejection. A timestamp of zero means "absent":
 * the stored {@code _timestamp} falls back to the accept instant, which is always inside the window.
 *
 * @param maxAge how far in the past a timestamp may be
 * @param maxFuture how far in the future a timestamp may be
 */
public record TimestampWindow(Duration maxAge, Duration maxFuture) {

	/** Below the 5 hours of OpenObserve, leaving room for clock skew and in flight retries. */
	public static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(4 * 60 + 50);

	public static final Duration DEFAULT_MAX_FUTURE = Duration.ofHours(1);

	public static final TimestampWindow DEFAULT = new TimestampWindow(DEFAULT_MAX_AGE, DEFAULT_MAX_FUTURE);

	public TimestampWindow {
		Objects.requireNonNull(maxAge, "maxAge");
		Objects.requireNonNull(maxFuture, "maxFuture");
		if (maxAge.isNegative() || maxAge.isZero() || maxFuture.isNegative()) {
			throw new IllegalArgumentException("maxAge must be positive and maxFuture must not be negative");
		}
	}

	/**
	 * Whether a timestamp in unsigned nanoseconds since the epoch is inside the window around {@code now}.
	 * Zero (absent) is always accepted.
	 */
	public boolean accepts(long unixNano, Instant now) {
		if (unixNano == 0) {
			return true;
		}
		if (unixNano < 0) {
			// above Long.MAX_VALUE as unsigned: year 2262 or later
			return false;
		}
		Instant time = Instant.ofEpochSecond(0, unixNano);
		return !time.isBefore(now.minus(maxAge)) && !time.isAfter(now.plus(maxFuture));
	}

}
