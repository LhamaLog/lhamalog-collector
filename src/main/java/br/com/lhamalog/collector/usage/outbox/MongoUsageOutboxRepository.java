package br.com.lhamalog.collector.usage.outbox;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Mongo persistence for usage outbox candidates. Duplicate key 11000 is the only
 * write error treated as an idempotent retry; other write failures propagate.
 * Idempotency ignores {@code acceptedAt}. Terminal {@code SYNCED} and {@code ABANDONED}
 * documents receive {@code expireAt} aligned with the seven-day RF-012 window.
 */
public final class MongoUsageOutboxRepository implements UsageOutboxRepository {

	static final String COLLECTION = "usage_outbox";
	private static final int DUPLICATE_KEY_CODE = 11000;

	private final MongoTemplate mongo;

	public MongoUsageOutboxRepository(MongoTemplate mongo) {
		this.mongo = mongo;
		ensureIndexes();
	}

	void ensureIndexes() {
		mongo.indexOps(COLLECTION).createIndex(new Index()
				.named("usage_outbox_expire_at")
				.on("expireAt", Sort.Direction.ASC)
				.expire(0, TimeUnit.SECONDS));
		mongo.indexOps(COLLECTION).createIndex(new Index()
				.named("usage_outbox_accepted_ready")
				.on("state", Sort.Direction.ASC)
				.on("nextAttemptAt", Sort.Direction.ASC)
				.on("leaseUntil", Sort.Direction.ASC));
	}

	@Override
	public UsageOutboxItem insertPending(UsageFact fact, Instant now) {
		UsageOutboxItem item = UsageOutboxItem.pending(fact, now);
		try {
			Document document = new Document();
			mongo.getConverter().write(item, document);
			mongo.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY).insertOne(document);
			return item;
		}
		catch (RuntimeException ex) {
			if (!isDuplicateKey(ex)) {
				throw ex;
			}
			UsageOutboxItem existing = mongo.findById(fact.usageFactId(), UsageOutboxItem.class, COLLECTION);
			if (existing == null || isExpired(existing, now)) {
				if (existing != null) {
					mongo.remove(Query.query(Criteria.where("_id").is(existing.id())), COLLECTION);
				}
				return insertPending(fact, now);
			}
			if (!existing.fact().sameImmutableContent(fact)) {
				throw new IllegalStateException("usage fact id conflicts with immutable content", ex);
			}
			if (existing.state() == UsageOutboxState.ABANDONED) {
				return reopenAbandoned(existing, fact, now);
			}
			return existing;
		}
	}

	private UsageOutboxItem reopenAbandoned(UsageOutboxItem existing, UsageFact fact, Instant now) {
		UsageOutboxItem reopened = UsageOutboxItem.pending(fact, now);
		Query query = Query.query(Criteria.where("_id").is(existing.id()).and("state").is(UsageOutboxState.ABANDONED));
		if (!applyReplacement(query, reopened)) {
			return Optional.ofNullable(mongo.findById(existing.id(), UsageOutboxItem.class, COLLECTION)).orElse(reopened);
		}
		return Optional.ofNullable(mongo.findById(existing.id(), UsageOutboxItem.class, COLLECTION)).orElse(reopened);
	}

	static boolean isDuplicateKey(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof MongoWriteException write) {
				return write.getError().getCode() == DUPLICATE_KEY_CODE;
			}
			current = current.getCause();
		}
		return exception instanceof DuplicateKeyException;
	}

	private static boolean isExpired(UsageOutboxItem item, Instant now) {
		return item.expireAt() != null && !item.expireAt().isAfter(now);
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
		Criteria ready = new Criteria().andOperator(
				Criteria.where("state").is(UsageOutboxState.ACCEPTED),
				Criteria.where("nextAttemptAt").lte(instant),
				new Criteria().orOperator(
						Criteria.where("leaseUntil").is(null),
						Criteria.where("leaseUntil").exists(false),
						Criteria.where("leaseUntil").lte(instant)));
		return mongo.find(Query.query(ready), UsageOutboxItem.class, COLLECTION);
	}

	@Override
	public boolean compareAndSet(String usageFactId, UsageOutboxState expected, UsageOutboxItem replacement) {
		return applyReplacement(Query.query(Criteria.where("_id").is(usageFactId).and("state").is(expected)), replacement);
	}

	@Override
	public boolean compareAndSet(UsageOutboxItem expected, UsageOutboxItem replacement) {
		Criteria criteria = Criteria.where("_id").is(expected.id()).and("state").is(expected.state());
		criteria = expected.leaseOwner() == null
				? criteria.and("leaseOwner").is(null)
				: criteria.and("leaseOwner").is(expected.leaseOwner());
		criteria = expected.leaseUntil() == null
				? criteria.and("leaseUntil").is(null)
				: criteria.and("leaseUntil").is(expected.leaseUntil());
		return applyReplacement(Query.query(criteria), replacement);
	}

	private boolean applyReplacement(Query query, UsageOutboxItem replacement) {
		UsageOutboxItem previous = mongo.findAndReplace(query, replacement, FindAndReplaceOptions.empty(),
				UsageOutboxItem.class, COLLECTION);
		return previous != null;
	}
}
