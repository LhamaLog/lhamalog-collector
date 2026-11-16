package br.com.lhamalog.collector.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdTest {

	@AfterEach
	void clearMdc() {
		CorrelationId.clear();
	}

	@Test
	void isValidAcceptsAllowedCharsetWithinLength() {
		assertThat(CorrelationId.isValid("abc-123_XYZ.ok")).isTrue();
		assertThat(CorrelationId.isValid("a".repeat(64))).isTrue();
	}

	@Test
	void isValidRejectsMissingInvalidOrTooLong() {
		assertThat(CorrelationId.isValid(null)).isFalse();
		assertThat(CorrelationId.isValid("")).isFalse();
		assertThat(CorrelationId.isValid("bad id")).isFalse();
		assertThat(CorrelationId.isValid("has@sign")).isFalse();
		assertThat(CorrelationId.isValid("a".repeat(65))).isFalse();
	}

	@Test
	void resolveKeepsValidAndReplacesInvalid() {
		assertThat(CorrelationId.resolve("tenant-request-1")).isEqualTo("tenant-request-1");
		String generated = CorrelationId.resolve("not valid!");
		assertThat(CorrelationId.isValid(generated)).isTrue();
		assertThat(generated).isNotEqualTo("not valid!");
	}

	@Test
	void newIdIsValidUuid() {
		String id = CorrelationId.newId();
		assertThat(CorrelationId.isValid(id)).isTrue();
		assertThat(id).matches("^[0-9a-fA-F-]{36}$");
	}

	@Test
	void putAndClearRoundTripMdc() {
		CorrelationId.put("cid-1");
		assertThat(CorrelationId.current()).isEqualTo("cid-1");
		assertThat(MDC.get(CorrelationId.KEY)).isEqualTo("cid-1");
		CorrelationId.clear();
		assertThat(CorrelationId.current()).isNull();
	}

	@Test
	void propagateRunsTaskWithCallerIdOnExecutorThreadAndCleansUp() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			CorrelationId.put("cid-caller");
			String seen = CompletableFuture.supplyAsync(CorrelationId.propagate(CorrelationId::current), executor).get();
			assertThat(seen).isEqualTo("cid-caller");
			CorrelationId.clear();
			String after = CompletableFuture.supplyAsync(CorrelationId::current, executor).get();
			assertThat(after).isNull();
		}
		finally {
			executor.shutdownNow();
		}
	}

}
