package br.com.lhamalog.collector.quota;

import java.time.Instant;
import java.util.List;

public interface RefundOutboxRepository {
	void enqueue(String reservationId, String reason, Instant now);

	List<RefundOutboxItem> ready(Instant now);

	boolean replace(RefundOutboxItem expected, RefundOutboxItem replacement);
}
