// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.auth;

import br.com.lhamalog.collector.ingestion.otlp.domain.ProductToken;
import br.com.lhamalog.collector.ingestion.otlp.domain.ProductTokenValidity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link TokenValidator} backed by the MongoDB collections {@code product_tokens} and {@code products}
 * (T008). Read only.
 *
 * <p>The Bearer value is hashed (SHA-256, lowercase hex of the UTF-8 bytes) and looked up by
 * {@code tokenHash}; the product of the token is read to get its {@code tenantId}. The decision is delegated
 * to {@link ProductTokenValidity}. Hits and misses are cached by hash with Caffeine (30 seconds by default),
 * so a revocation shows up within the TTL. A MongoDB failure is "catalog unavailable" and is not cached. The
 * token value is never logged nor kept in memory beyond the call.
 */
public class MongoProductTokenStore implements TokenValidator {

	public static final String PRODUCTS_COLLECTION = "products";

	public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

	private static final Logger log = LoggerFactory.getLogger(MongoProductTokenStore.class);

	private final MongoOperations mongo;

	private final Clock clock;

	private final Cache<String, TokenValidationResult> cache;

	public MongoProductTokenStore(MongoOperations mongo, Clock clock, Duration cacheTtl) {
		this.mongo = mongo;
		this.clock = clock;
		this.cache = Caffeine.newBuilder().expireAfterWrite(cacheTtl).maximumSize(10_000).build();
	}

	@Override
	public TokenValidationResult validate(String bearerToken) {
		if (bearerToken == null || bearerToken.isBlank()) {
			return new TokenValidationResult.Unauthorized();
		}
		String hash = sha256Hex(bearerToken);
		TokenValidationResult cached = cache.getIfPresent(hash);
		if (cached != null) {
			return cached;
		}
		TokenValidationResult result;
		try {
			result = lookup(hash);
		}
		catch (RuntimeException ex) {
			log.warn("Token catalog unavailable: {}", ex.getClass().getSimpleName());
			return new TokenValidationResult.CatalogUnavailable(ex);
		}
		cache.put(hash, result);
		return result;
	}

	private TokenValidationResult lookup(String hash) {
		Query byHash = Query.query(Criteria.where("tokenHash").is(hash)).limit(2);
		List<Document> documents = mongo.find(byHash, Document.class, ProductToken.COLLECTION);
		List<ProductToken> matches = new ArrayList<>(documents.size());
		for (Document document : documents) {
			Optional<ProductToken> token = toProductToken(document);
			if (token.isEmpty()) {
				return new TokenValidationResult.Unauthorized();
			}
			matches.add(token.get());
		}
		Optional<ProductToken> usable = ProductTokenValidity.usableToken(matches, clock.instant());
		if (usable.isEmpty()) {
			return new TokenValidationResult.Unauthorized();
		}
		String productId = usable.get().productId();
		Document product = mongo.findOne(Query.query(Criteria.where("productId").is(productId)), Document.class,
				PRODUCTS_COLLECTION);
		String tenantId = product != null && product.get("tenantId") instanceof String value ? value : null;
		return ProductTokenValidity.tenantOf(product != null, tenantId)
			.<TokenValidationResult>map(tenant -> new TokenValidationResult.Valid(tenant, productId))
			.orElseGet(TokenValidationResult.Unauthorized::new);
	}

	/** Maps a raw document; a document missing required fields cannot authorize anything. */
	static Optional<ProductToken> toProductToken(Document document) {
		try {
			Object id = document.get("_id");
			return Optional.of(new ProductToken(id == null ? null : id.toString(), document.getString("productId"),
					document.getString("tokenHash"), ProductToken.Status.valueOf(document.getString("status")),
					instant(document.get("createdAt")), instant(document.get("revokedAt")),
					instant(document.get("expiresAt"))));
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	private static Instant instant(Object value) {
		return switch (value) {
			case null -> null;
			case Date date -> date.toInstant();
			case Instant instant -> instant;
			default -> throw new IllegalArgumentException("not a date");
		};
	}

	/** Lowercase hex SHA-256 of the UTF-8 bytes of the token, as stored in {@code tokenHash}. */
	public static String sha256Hex(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
