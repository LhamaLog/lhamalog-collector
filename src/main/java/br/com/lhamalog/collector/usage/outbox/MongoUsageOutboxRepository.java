package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import com.mongodb.WriteConcern;
import com.mongodb.MongoWriteException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public final class MongoUsageOutboxRepository implements UsageOutboxRepository {

	static final String COLLECTION = "usage_outbox";

	private final MongoTemplate mongo;

	public MongoUsageOutboxRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	@Override
	public UsageOutboxItem insertPending(UsageFact fact, Instant now) {
		UsageOutboxItem item = UsageOutboxItem.pending(fact, now);
		try {
			org.bson.Document document = new org.bson.Document();
			mongo.getConverter().write(item, document);
			mongo.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY).insertOne(document);
			return item;
		}
		catch (DuplicateKeyException | MongoWriteException ex) {
			UsageOutboxItem existing = mongo.findById(fact.usageFactId(), UsageOutboxItem.class, COLLECTION);
			if (existing != null && existing.fact().equals(fact)) {
				return existing;
			}
			throw new IllegalStateException("usage fact id conflicts with immutable content", ex);
		}
	}

	@Override
	public Optional<UsageOutboxItem> findById(String usageFactId) {
		return Optional.ofNullable(mongo.findById(usageFactId, UsageOutboxItem.class, COLLECTION));
	}

	@Override
	public List<UsageOutboxItem> findPendingBefore(Instant instant) {
		return mongo.find(Query.query(Criteria.where("state").is(UsageOutboxState.PENDING)
			.and("createdAt").lte(instant)), UsageOutboxItem.class, COLLECTION);
	}

	@Override
	public List<UsageOutboxItem> findAcceptedReady(Instant instant) {
		return mongo.find(Query.query(Criteria.where("state").is(UsageOutboxState.ACCEPTED)
			.and("nextAttemptAt").lte(instant)), UsageOutboxItem.class, COLLECTION);
	}

	@Override
	public boolean compareAndSet(String usageFactId, UsageOutboxState expected, UsageOutboxItem replacement) {
		Query query = Query.query(Criteria.where("_id").is(usageFactId).and("state").is(expected));
		return mongo.updateFirst(query, Update.fromDocument(
				new org.bson.Document("$set", new org.bson.Document("state", replacement.state().name())
					.append("attemptCount", replacement.attemptCount()).append("nextAttemptAt", replacement.nextAttemptAt())
					.append("leaseOwner", replacement.leaseOwner()).append("leaseUntil", replacement.leaseUntil())
					.append("lastErrorCode", replacement.lastErrorCode()).append("deliveredAt", replacement.deliveredAt()))),
				COLLECTION).getModifiedCount() == 1;
	}
}
