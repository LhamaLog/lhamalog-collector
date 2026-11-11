package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class E2eFaultInjectingBatchStoreTest {

	@Test
	void staysInactiveByDefaultAndFailsOnlyWhenEnabledMarkerIsPresent() {
		AtomicInteger calls = new AtomicInteger();
		StoredBatch ok = new StoredBatch(Signal.LOGS, "org", "logs", "batch", List.of("r1"), 1, 1,
				"2026-11-11T12:00:00Z");
		BatchStore<LogRecord> delegate = (tenant, product, records) -> {
			calls.incrementAndGet();
			return ok;
		};
		LogRecord marker = LogRecord.newBuilder()
				.setBody(AnyValue.newBuilder().setStringValue("payload __e2e_write_fail__"))
				.build();
		List<Scoped<LogRecord>> records = List.of(Scoped.of(marker));

		assertThat(new E2eFaultInjectingBatchStore<>(delegate, false).store("t", "p", records)).isEqualTo(ok);
		assertThatThrownBy(() -> new E2eFaultInjectingBatchStore<>(delegate, true).store("t", "p", records))
				.isInstanceOf(SinkUnavailableException.class);
		assertThat(calls).hasValue(1);
	}
}
