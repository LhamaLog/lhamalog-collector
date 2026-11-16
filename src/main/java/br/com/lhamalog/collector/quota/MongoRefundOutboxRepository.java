package br.com.lhamalog.collector.quota;

import com.mongodb.WriteConcern;
import com.mongodb.MongoWriteException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Durable, idempotent compensation requests keyed by Core reservation id. */
public final class MongoRefundOutboxRepository implements RefundOutboxRepository {
	static final String COLLECTION = "quota_refund_outbox";
	private final MongoTemplate mongo;

	public MongoRefundOutboxRepository(MongoTemplate mongo) {
		this.mongo = mongo;
		mongo.indexOps(COLLECTION).createIndex(new Index().named("quota_refund_ready")
				.on("state", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC));
	}

	@Override
	public void enqueue(String reservationId, String reason, Instant now) {
		try {
			mongo.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY)
					.insertOne(toDocument(RefundOutboxItem.pending(reservationId, reason, now)));
		}
		catch (RuntimeException exception) {
			if (!duplicateKey(exception)) throw exception;
			// One refund is sufficient; the existing durable item owns delivery.
		}
	}

	@Override
	public List<RefundOutboxItem> ready(Instant now) {
		return mongo.find(Query.query(Criteria.where("state").is(RefundOutboxState.PENDING)
				.and("nextAttemptAt").lte(now)), RefundOutboxItem.class, COLLECTION);
	}

	@Override
	public boolean replace(RefundOutboxItem expected, RefundOutboxItem replacement) {
		Query query = Query.query(Criteria.where("_id").is(expected.reservationId())
				.and("state").is(expected.state())
				.and("attemptCount").is(expected.attemptCount()));
		return mongo.findAndReplace(query, replacement, FindAndReplaceOptions.empty(), RefundOutboxItem.class,
				COLLECTION) != null;
	}

	private org.bson.Document toDocument(RefundOutboxItem item) {
		org.bson.Document document = new org.bson.Document("_id", item.reservationId());
		mongo.getConverter().write(item, document);
		document.put("_id", item.reservationId());
		return document;
	}

	private static boolean duplicateKey(Throwable exception) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (current instanceof MongoWriteException write && write.getError().getCode() == 11000) return true;
		}
		return exception instanceof DuplicateKeyException;
	}
}
