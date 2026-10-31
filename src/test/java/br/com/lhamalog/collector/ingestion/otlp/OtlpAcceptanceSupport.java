// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp;

import br.com.lhamalog.collector.ingestion.otlp.auth.MongoProductTokenStore;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers of the acceptance tests: product and token seeded on the fly, OpenObserve queried by search. */
final class OtlpAcceptanceSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final SecureRandom RANDOM = new SecureRandom();

	static final HttpClient HTTP = HttpClient.newHttpClient();

	private OtlpAcceptanceSupport() {
	}

	/** Product with tenant and a fresh random token; only the SHA-256 goes to MongoDB. */
	record SeededProduct(String tenantId, String productId, String token) {
	}

	static SeededProduct seedProduct(MongoOperations mongo) {
		String tenantId = "tenant-" + UUID.randomUUID();
		String productId = "product-" + UUID.randomUUID();
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		mongo.insert(new Document(Map.of("productId", productId, "tenantId", tenantId, "name", "acceptance")), "products");
		mongo.insert(new Document(Map.of("productId", productId, "tokenHash", MongoProductTokenStore.sha256Hex(token), "status",
				"ACTIVE", "createdAt", new Date())), "product_tokens");
		return new SeededProduct(tenantId, productId, token);
	}

	static ManagedChannel channel(int port) {
		return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
	}

	static io.grpc.ClientInterceptor bearer(String token) {
		Metadata headers = new Metadata();
		headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
		return MetadataUtils.newAttachHeadersInterceptor(headers);
	}

	static HttpResponse<byte[]> postProtobuf(int port, String path, String token, byte[] body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.header("Content-Type", "application/x-protobuf")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	static long nowNanos() {
		Instant now = Instant.now();
		return now.getEpochSecond() * 1_000_000_000L + now.getNano();
	}

	/**
	 * Polls OpenObserve until a document of {@code stream} has {@code field} equal to {@code value}. Field names
	 * are compared ignoring case, since OpenObserve may normalize them.
	 */
	static List<JsonNode> awaitDocuments(Environment env, String stream, String field, String value) throws Exception {
		long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		List<JsonNode> found = List.of();
		while (System.nanoTime() < end) {
			found = search(env, stream, field, value);
			if (!found.isEmpty()) {
				return found;
			}
			Thread.sleep(1000);
		}
		return found;
	}

	// O _json grava os dois streams como tipo logs, inclusive o de traces.
	static List<JsonNode> search(Environment env, String stream, String field, String value) throws Exception {
		long nowMicros = System.currentTimeMillis() * 1000;
		String body = JSON.writeValueAsString(Map.of("query", Map.of("sql", "SELECT * FROM \"" + stream + "\"", "start_time",
				nowMicros - 3_600_000_000L, "end_time", nowMicros + 3_600_000_000L, "from", 0, "size", 1000)));
		String credential = env.getRequiredProperty("lhamalog.otlp.openobserve.username") + ":"
				+ env.getRequiredProperty("lhamalog.otlp.openobserve.password");
		HttpRequest request = HttpRequest
			.newBuilder(URI.create(env.getRequiredProperty("lhamalog.otlp.openobserve.url") + "/api/"
					+ env.getProperty("lhamalog.otlp.openobserve.organization", "lhamalog") + "/_search?type=logs"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		List<JsonNode> matches = new ArrayList<>();
		if (response.statusCode() != 200) {
			return matches;
		}
		for (JsonNode hit : JSON.readTree(response.body()).path("hits")) {
			if (value.equals(text(hit, field))) {
				matches.add(hit);
			}
		}
		return matches;
	}

	static String text(JsonNode document, String field) {
		for (String name : document.propertyNames()) {
			if (name.equalsIgnoreCase(field)) {
				return document.get(name).asString();
			}
		}
		return null;
	}

}
