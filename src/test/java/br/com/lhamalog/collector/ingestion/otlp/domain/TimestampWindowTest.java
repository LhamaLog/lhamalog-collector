// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Accepted timestamp window (T040, FR-019). */
class TimestampWindowTest {

	private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

	private static final TimestampWindow WINDOW = new TimestampWindow(Duration.ofHours(4), Duration.ofMinutes(10));

	private static long nanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	@Test
	void limitsAreInclusive() {
		assertThat(WINDOW.accepts(nanos(NOW), NOW)).isTrue();
		assertThat(WINDOW.accepts(nanos(NOW.minus(Duration.ofHours(4))), NOW)).isTrue();
		assertThat(WINDOW.accepts(nanos(NOW.plus(Duration.ofMinutes(10))), NOW)).isTrue();
	}

	@Test
	void tooOldOrTooFarInTheFutureIsRejected() {
		assertThat(WINDOW.accepts(nanos(NOW.minus(Duration.ofHours(4)).minusNanos(1)), NOW)).isFalse();
		assertThat(WINDOW.accepts(nanos(NOW.plus(Duration.ofMinutes(10)).plusNanos(1)), NOW)).isFalse();
		assertThat(WINDOW.accepts(1, NOW)).isFalse();
	}

	@Test
	void zeroMeansAbsentAndHugeUnsignedValuesAreRejected() {
		assertThat(WINDOW.accepts(0, NOW)).isTrue();
		assertThat(WINDOW.accepts(-1L, NOW)).isFalse();
		assertThat(WINDOW.accepts(Long.MAX_VALUE, NOW)).isFalse();
	}

	@Test
	void defaultStaysBelowOpenObserveFiveHours() {
		assertThat(TimestampWindow.DEFAULT.maxAge()).isLessThan(Duration.ofHours(5));
	}

	@Test
	void invalidConfigurationFailsFast() {
		assertThatThrownBy(() -> new TimestampWindow(Duration.ZERO, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TimestampWindow(Duration.ofHours(1), Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
