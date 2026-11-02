// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;

/**
 * Logs destination that writes to OpenObserve first and then publishes one {@code log.ingested} reference
 * to the Redis Stream of the Varredura (LLT-182, see {@code specs/002-tenant-product-logs/research.md}).
 *
 * <ul>
 * <li>Only batches that passed token validation and record assessment get here.</li>
 * <li>When OpenObserve fails or refuses any record, its exception propagates and nothing is published.</li>
 * <li>When Redis does not confirm, the failure becomes {@link SinkUnavailableException} (503 /
 * {@code UNAVAILABLE}); the client retry rewrites OpenObserve with the same ids and publishes the same
 * batch id.</li>
 * </ul>
 */
public class PublishingLogsSink implements LogsSink {

	/** Publishes the reference of one stored batch; mirrors {@link LogIngestedPublisher#publishBatch}. */
	@FunctionalInterface
	public interface Publisher {

		void publish(String tenantId, String productId, StoredBatch batch, List<Scoped<LogRecord>> records);

	}

	private final BatchStore<LogRecord> store;

	private final Publisher publisher;

	public PublishingLogsSink(BatchStore<LogRecord> store, Publisher publisher) {
		this.store = store;
		this.publisher = publisher;
	}

	public static PublishingLogsSink of(BatchStore<LogRecord> store, LogIngestedPublisher publisher) {
		return new PublishingLogsSink(store, publisher::publishBatch);
	}

	@Override
	public void deliver(String tenantId, String productId, List<Scoped<LogRecord>> acceptedRecords) {
		StoredBatch batch = store.store(tenantId, productId, acceptedRecords);
		if (acceptedRecords.isEmpty()) {
			return;
		}
		try {
			publisher.publish(tenantId, productId, batch, acceptedRecords);
		}
		catch (MessagePublishException ex) {
			throw new SinkUnavailableException("Message queue did not confirm the logs reference", ex);
		}
	}

}
