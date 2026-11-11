// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;

/**
 * Logs destination that writes to OpenObserve first and then publishes one {@code log.ingested} reference
 * to the Redis Stream of the Varredura (LLT-182, see {@code specs/002-tenant-product-logs/research.md}).
 *
 * <ul>
 * <li>Only batches that passed token validation and record assessment get here.</li>
 * <li>When OpenObserve fails or refuses any record, its exception propagates and nothing is published.</li>
 * <li>Publish retries Redis internally and may land on the logs DLQ (LLT-184). Only when both the main
 * stream and the DLQ fail does the failure become {@link SinkUnavailableException} (503 /
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

	/** Usage-aware publication; its result is the Redis evidence, never stream inspection. */
	@FunctionalInterface
	public interface UsagePublisher {

		String publish(String tenantId, String productId, StoredBatch batch, List<Scoped<LogRecord>> records,
				String evidenceKey);

	}

	private final BatchStore<LogRecord> store;

	private final Publisher publisher;

	private final UsageFactFactory usageFacts;

	private final UsageFactRecorder usageRecorder;

	private final UsagePublisher usagePublisher;

	public PublishingLogsSink(BatchStore<LogRecord> store, Publisher publisher) {
		this(store, publisher, null, null, null);
	}

	public PublishingLogsSink(BatchStore<LogRecord> store, Publisher publisher, UsageFactFactory usageFacts,
			UsageFactRecorder usageRecorder) {
		this(store, publisher, usageFacts, usageRecorder, null);
	}

	public PublishingLogsSink(BatchStore<LogRecord> store, Publisher publisher, UsageFactFactory usageFacts,
			UsageFactRecorder usageRecorder, UsagePublisher usagePublisher) {
		this.store = store;
		this.publisher = publisher;
		this.usageFacts = usageFacts;
		this.usageRecorder = usageRecorder;
		this.usagePublisher = usagePublisher != null ? usagePublisher
				: (tenantId, productId, batch, records, evidenceKey) -> {
					publisher.publish(tenantId, productId, batch, records);
					return "DONE:disabled";
				};
	}

	public static PublishingLogsSink of(BatchStore<LogRecord> store, LogIngestedPublisher publisher) {
		return new PublishingLogsSink(store, publisher::publishBatch);
	}

	@Override
	public void deliver(String tenantId, String productId, List<Scoped<LogRecord>> acceptedRecords) {
		deliver(tenantId, productId, null, acceptedRecords);
	}

	@Override
	public void deliver(String tenantId, String productId, ExportLogsServiceRequest request,
			List<Scoped<LogRecord>> acceptedRecords) {
		StoredBatch batch = store.store(tenantId, productId, acceptedRecords);
		if (acceptedRecords.isEmpty()) {
			return;
		}
		UsageOutboxItem candidate = null;
		try {
			if (request != null && usageFacts != null && usageRecorder != null) {
				UsageFact fact = usageFacts.logs(tenantId, productId, request, acceptedRecords, batch);
				candidate = usageRecorder.recordPending(fact);
			}
			if (candidate != null) {
				confirmUsageEvidence(usagePublisher.publish(tenantId, productId, batch, acceptedRecords,
						candidate.acceptedEvidenceKey()), "logs");
			}
			else {
				publisher.publish(tenantId, productId, batch, acceptedRecords);
			}
			if (candidate != null && candidate.state() == br.com.lhamalog.collector.usage.outbox.UsageOutboxState.PENDING) {
				usageRecorder.accept(candidate);
			}
		}
		catch (MessagePublishException | IllegalStateException ex) {
			throw new SinkUnavailableException("Message queue did not confirm the logs reference", ex);
		}
	}

	static void confirmUsageEvidence(String evidence, String domain) {
		if (evidence != null && evidence.startsWith("DONE:")) {
			return;
		}
		if (evidence != null && evidence.startsWith("CLAIMED:")) {
			throw new SinkUnavailableException(domain + " usage evidence is claimed pending confirmation", null);
		}
		if (evidence != null && evidence.startsWith("ABANDONED")) {
			throw new SinkUnavailableException(domain + " usage evidence was abandoned", null);
		}
		throw new SinkUnavailableException("Redis did not confirm " + domain + " usage evidence", null);
	}

}

