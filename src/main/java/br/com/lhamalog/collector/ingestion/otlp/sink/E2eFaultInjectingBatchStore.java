package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import java.util.List;

/** Narrow, opt-in E2E seam; production batches never activate it. */
public final class E2eFaultInjectingBatchStore<T> implements BatchStore<T> {
	private final BatchStore<T> delegate;
	private final boolean enabled;

	public E2eFaultInjectingBatchStore(BatchStore<T> delegate, boolean enabled) {
		this.delegate = delegate;
		this.enabled = enabled;
	}

	@Override
	public StoredBatch store(String tenantId, String productId, List<Scoped<T>> records) {
		if (enabled && records.stream().anyMatch(record -> record.record().toString().contains("__e2e_write_fail__"))) {
			throw new SinkUnavailableException("E2E injected sink write failure", null);
		}
		return delegate.store(tenantId, productId, records);
	}
}
