// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;

/**
 * Logs destination that writes to OpenObserve first and then publishes the same logs to the Redis Stream of
 * the Varredura (LLT-182, see {@code specs/002-tenant-product-logs/research.md}).
 *
 * <ul>
 * <li>The delegate runs only for batches that passed token validation and record assessment, so an
 * unauthenticated or invalid log never reaches the queue.</li>
 * <li>When OpenObserve fails or refuses any record, its exception propagates and nothing is published.</li>
 * <li>When Redis does not confirm, the failure becomes {@link SinkUnavailableException}, which the receiver
 * answers with 503 / {@code UNAVAILABLE}. The client retry writes OpenObserve again (at least once) and
 * publishes with the same {@code idempotencyId}.</li>
 * </ul>
 */
public class PublishingLogsSink implements LogsSink {

	/** Publishes one accepted batch; mirrors {@link LogIngestedPublisher#publishLogs}. */
	@FunctionalInterface
	public interface Publisher {

		void publish(String tenantId, String productId, List<Scoped<LogRecord>> records);

	}

	private final LogsSink delegate;

	private final Publisher publisher;

	public PublishingLogsSink(LogsSink delegate, Publisher publisher) {
		this.delegate = delegate;
		this.publisher = publisher;
	}

	public static PublishingLogsSink of(LogsSink delegate, LogIngestedPublisher publisher) {
		return new PublishingLogsSink(delegate, publisher::publishLogs);
	}

	@Override
	public void deliver(String tenantId, String productId, List<Scoped<LogRecord>> acceptedRecords) {
		delegate.deliver(tenantId, productId, acceptedRecords);
		if (acceptedRecords.isEmpty()) {
			return;
		}
		try {
			publisher.publish(tenantId, productId, acceptedRecords);
		}
		catch (MessagePublishException ex) {
			throw new SinkUnavailableException("Message queue did not confirm the logs", ex);
		}
	}

}
