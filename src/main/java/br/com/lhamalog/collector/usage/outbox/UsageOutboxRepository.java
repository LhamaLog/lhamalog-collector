package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UsageOutboxRepository {

	UsageOutboxItem insertPending(UsageFact fact, Instant now);

	Optional<UsageOutboxItem> findById(String usageFactId);

	List<UsageOutboxItem> findPendingBefore(Instant instant);

	List<UsageOutboxItem> findAcceptedReady(Instant instant);

	boolean compareAndSet(String usageFactId, UsageOutboxState expected, UsageOutboxItem replacement);

	default boolean compareAndSet(UsageOutboxItem expected, UsageOutboxItem replacement) {
		return compareAndSet(expected.id(), expected.state(), replacement);
	}
}
