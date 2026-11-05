// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import java.util.List;

/**
 * Writes one accepted batch to OpenObserve and tells where it landed, so the stream can carry only a
 * reference (LLT-182, {@code specs/002-tenant-product-logs/contracts/log-ingested-stream.md}).
 */
@FunctionalInterface
public interface BatchStore<T> {

	/**
	 * @throws SinkUnavailableException if OpenObserve is down or does not confirm every record
	 * @throws SinkRejectedException if OpenObserve refuses records as invalid data
	 */
	StoredBatch store(String tenantId, String productId, List<Scoped<T>> records);

}
