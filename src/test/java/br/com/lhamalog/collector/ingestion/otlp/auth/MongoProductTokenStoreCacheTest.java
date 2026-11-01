// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.lhamalog.collector.ingestion.otlp.domain.ProductToken;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

/** Positive and negative token caches (T042, FR-008), with a fake MongoDB and a fake cache clock. */
class MongoProductTokenStoreCacheTest {

	private static final String GOOD = "good-token";

	private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

	private final AtomicLong nanos = new AtomicLong();

	private final Ticker ticker = nanos::get;

	private MongoOperations mongo;

	private MongoProductTokenStore store;

	@BeforeEach
	void setUp() {
		mongo = mock(MongoOperations.class);
		String goodHash = MongoProductTokenStore.sha256Hex(GOOD);
		when(mongo.find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION))).thenAnswer(invocation -> {
			Query query = invocation.getArgument(0);
			if (!query.getQueryObject().toJson().contains(goodHash)) {
				return List.of();
			}
			return List.of(new Document("_id", "t1").append("productId", "product-a")
				.append("tokenHash", goodHash)
				.append("status", "ACTIVE")
				.append("createdAt", Date.from(NOW.minus(Duration.ofDays(1)))));
		});
		when(mongo.findOne(any(Query.class), eq(Document.class), eq(MongoProductTokenStore.PRODUCTS_COLLECTION)))
			.thenReturn(new Document("productId", "product-a").append("tenantId", "tenant-1"));
		store = new MongoProductTokenStore(mongo, Clock.fixed(NOW, ZoneOffset.UTC),
				new MongoProductTokenStore.CacheSettings(Duration.ofSeconds(30), 10, Duration.ofSeconds(3), 5), ticker);
	}

	@Test
	void validTokenIsCachedInThePositiveCache() {
		assertThat(store.validate(GOOD)).isInstanceOf(TokenValidationResult.Valid.class);
		assertThat(store.validate(GOOD)).isInstanceOf(TokenValidationResult.Valid.class);
		verify(mongo, times(1)).find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION));
		assertThat(store.positiveCacheSize()).isEqualTo(1);
		assertThat(store.negativeCacheSize()).isZero();
	}

	@Test
	void floodOfRandomTokensDoesNotEvictTheValidEntry() {
		store.validate(GOOD);
		for (int i = 0; i < 1_000; i++) {
			assertThat(store.validate("random-" + i)).isInstanceOf(TokenValidationResult.Unauthorized.class);
		}
		assertThat(store.negativeCacheSize()).isLessThanOrEqualTo(5);
		assertThat(store.positiveCacheSize()).isEqualTo(1);
		assertThat(store.validate(GOOD)).isInstanceOf(TokenValidationResult.Valid.class);
		// 1 lookup of the good token + 1,000 of random ones; the second good call came from the cache
		verify(mongo, times(1_001)).find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION));
	}

	@Test
	void refusedTokenExpiresQuicklyFromTheNegativeCache() {
		store.validate("unknown");
		store.validate("unknown");
		verify(mongo, times(1)).find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION));
		nanos.addAndGet(Duration.ofSeconds(4).toNanos());
		store.validate("unknown");
		verify(mongo, times(2)).find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION));
	}

	@Test
	void catalogFailureIsNotCached() {
		when(mongo.find(any(Query.class), eq(Document.class), eq(ProductToken.COLLECTION)))
			.thenThrow(new IllegalStateException("down"));
		assertThat(store.validate(GOOD)).isInstanceOf(TokenValidationResult.CatalogUnavailable.class);
		assertThat(store.positiveCacheSize()).isZero();
		assertThat(store.negativeCacheSize()).isZero();
	}

	@Test
	void invalidCacheSettingsFailFast() {
		assertThatThrownBy(() -> new MongoProductTokenStore.CacheSettings(Duration.ZERO, 1, Duration.ofSeconds(1), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MongoProductTokenStore.CacheSettings(Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
