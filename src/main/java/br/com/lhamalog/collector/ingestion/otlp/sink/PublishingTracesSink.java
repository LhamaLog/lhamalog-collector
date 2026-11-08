// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;

/**
 * Traces destination that writes to OpenObserve first and then publishes one {@code trace.ingested} reference
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
public class PublishingTracesSink implements TracesSink {

	/** Publishes the reference of one stored batch; mirrors {@link TraceIngestedPublisher#publishBatch}. */
	@FunctionalInterface
	public interface Publisher {

		void publish(String tenantId, String productId, StoredBatch batch, List<Scoped<Span>> records);

	}

	private final BatchStore<Span> store;

	private final Publisher publisher;

	public PublishingTracesSink(BatchStore<Span> store, Publisher publisher) {
		this.store = store;
		this.publisher = publisher;
	}

	public static PublishingTracesSink of(BatchStore<Span> store, TraceIngestedPublisher publisher) {
		return new PublishingTracesSink(store, publisher::publishBatch);
	}

	@Override
	public void deliver(String tenantId, String productId, List<Scoped<Span>> acceptedSpans) {
		StoredBatch batch = store.store(tenantId, productId, acceptedSpans);
		if (acceptedSpans.isEmpty()) {
			return;
		}
		try {
			publisher.publish(tenantId, productId, batch, acceptedSpans);
		}
		catch (MessagePublishException ex) {
			throw new SinkUnavailableException("Message queue did not confirm the traces reference", ex);
		}
	}

}
