package br.com.lhamalog.collector.usage.core;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for POST /internal/usage-facts. Sends only the dedicated service
 * token header and the usage fact JSON produced by the project's JsonMapper.
 */
public final class CoreUsageFactsClient implements UsageFactsClient {

	private final HttpClient http;
	private final URI endpoint;
	private final CoreAuthenticationProvider authentication;
	private final JsonMapper json;

	public CoreUsageFactsClient(HttpClient http, URI coreUrl, CoreAuthenticationProvider authentication) {
		this(http, coreUrl, authentication, JsonMapper.builder().build());
	}

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CoreUsageFactsClient.class);

	public CoreUsageFactsClient(HttpClient http, URI coreUrl, CoreAuthenticationProvider authentication,
			JsonMapper json) {
		this.http = http;
		this.endpoint = coreUrl.resolve("/internal/usage-facts");
		this.authentication = authentication;
		this.json = json;
		log.info("Core usage-facts client endpoint={}", this.endpoint);
	}

	@Override
	public int send(UsageFact fact) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.header("X-LhamaLog-Service-Token", authentication.serviceToken())
			.POST(HttpRequest.BodyPublishers.ofString(toJson(fact)))
			.build();
		log.info("Sending usage fact to {} headerNames={} authorizationPresent={} contentType={} tokenLength={} tokenSha256={}",
				endpoint, request.headers().map().keySet(),
				request.headers().firstValue("Authorization").isPresent(),
				request.headers().firstValue("Content-Type").orElse("?"),
				authentication.serviceToken().length(),
				CoreAuthenticationProvider.sha256Hex(authentication.serviceToken()));
		int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		log.info("Core usage-fact response {}", status);
		return status;
	}

	private String toJson(UsageFact fact) throws IOException {
		try {
			return json.writeValueAsString(new UsageFactPayload(fact.usageFactId(), fact.batchId(), fact.tenantId(),
					fact.productId(), fact.domain().name(), fact.acceptedRecordIds(), fact.acceptedRecordCount(),
					fact.acceptedBytes(), fact.acceptedAt().toString(), fact.schemaVersion()));
		}
		catch (JacksonException ex) {
			throw new IOException("Unable to serialize usage fact", ex);
		}
	}

	public record UsageFactPayload(String usageFactId, String batchId, String tenantId, String productId, String domain,
			List<String> acceptedRecordIds, int acceptedRecordCount, long acceptedBytes, String acceptedAt,
			int schemaVersion) {
	}
}
