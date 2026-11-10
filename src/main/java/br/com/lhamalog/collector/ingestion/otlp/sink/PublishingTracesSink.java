// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;

/**
 * Traces destination that writes to OpenObserve first and then publishes one {@code trace.ingested} reference
 * to the Redis Stream of the Varredura (LLT-182, see {@code specs/002-tenant-product-logs/research.md}).
 *
 * <ul>
 * <li>Only batches that passed token validation and record assessment get here.</li>
 * <li>When OpenObserve fails or refuses any record, its exception propagates and nothing is published.</li>
 * <li>Publish retries Redis internally and may land on the traces DLQ (LLT-184). Only when both the main
 * stream and the DLQ fail does the failure become {@link SinkUnavailableException} (503 /
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

	/** Usage-aware publication; its result is the Redis evidence, never stream inspection. */
	@FunctionalInterface
	public interface UsagePublisher {

		String publish(String tenantId, String productId, StoredBatch batch, List<Scoped<Span>> records,
				String evidenceKey);

	}

	private final BatchStore<Span> store;

	private final Publisher publisher;

	private final UsageFactFactory usageFacts;

	private final UsageFactRecorder usageRecorder;

	private final UsagePublisher usagePublisher;

	public PublishingTracesSink(BatchStore<Span> store, Publisher publisher) {
		this(store, publisher, null, null, null);
	}

	public PublishingTracesSink(BatchStore<Span> store, Publisher publisher, UsageFactFactory usageFacts,
			UsageFactRecorder usageRecorder) {
		this(store, publisher, usageFacts, usageRecorder, null);
	}

	public PublishingTracesSink(BatchStore<Span> store, Publisher publisher, UsageFactFactory usageFacts,
			UsageFactRecorder usageRecorder, UsagePublisher usagePublisher) {
		this.store = store;
		this.publisher = publisher;
		this.usageFacts = usageFacts;
		this.usageRecorder = usageRecorder;
		this.usagePublisher = usagePublisher;
	}

	public static PublishingTracesSink of(BatchStore<Span> store, TraceIngestedPublisher publisher) {
		return new PublishingTracesSink(store, publisher::publishBatch);
	}

	@Override
	public void deliver(String tenantId, String productId, List<Scoped<Span>> acceptedSpans) {
		deliver(tenantId, productId, null, acceptedSpans);
	}

	@Override
	public void deliver(String tenantId, String productId, ExportTraceServiceRequest request,
			List<Scoped<Span>> acceptedSpans) {
		StoredBatch batch = store.store(tenantId, productId, acceptedSpans);
		if (acceptedSpans.isEmpty()) {
			return;
		}
		UsageOutboxItem candidate = null;
		try {
			if (request != null && usageFacts != null && usageRecorder != null) {
				UsageFact fact = usageFacts.traces(tenantId, productId, request, acceptedSpans, batch);
				candidate = usageRecorder.recordPending(fact);
			}
			if (candidate != null && usagePublisher != null) {
				String evidence = usagePublisher.publish(tenantId, productId, batch, acceptedSpans,
						candidate.acceptedEvidenceKey());
				if (!evidence.startsWith("DONE:")) {
					throw new SinkUnavailableException("Redis did not confirm traces usage evidence", null);
				}
			}
			else {
				publisher.publish(tenantId, productId, batch, acceptedSpans);
			}
			if (candidate != null) {
				usageRecorder.accept(candidate);
			}
		}
		catch (MessagePublishException | IllegalStateException ex) {
			throw new SinkUnavailableException("Message queue did not confirm the traces reference", ex);
		}
	}

}
