// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes JSON documents to OpenObserve with {@code POST /api/{organization}/{stream}/_json} (ADR_168).
 *
 * <p>A write is confirmed only when the answer is HTTP 200, the sum of {@code failed} is zero and the sum
 * of {@code successful} equals the number of documents sent. Anything else, including a network error,
 * throws {@link SinkUnavailableException}; it never becomes an OTLP partial success. Basic auth comes from
 * the environment and the credential is never logged.
 */
public class OpenObserveClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;

	private final String baseUrl;

	private final String organization;

	private final String authorization;

	private final JsonMapper json = JsonMapper.builder().build();

	public OpenObserveClient(HttpClient http, String baseUrl, String organization, String username, String password) {
		this.http = http;
		this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
		this.organization = organization;
		this.authorization = username == null || username.isBlank() ? null
				: "Basic " + Base64.getEncoder()
					.encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
	}

	public void ingest(String stream, List<Map<String, Object>> documents) {
		if (baseUrl == null) {
			throw new SinkUnavailableException("OpenObserve URL is not configured");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/" + organization + "/" + stream + "/_json"))
			.timeout(TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(documents)));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		HttpResponse<String> response;
		try {
			response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new SinkUnavailableException("OpenObserve is unreachable: " + ex.getClass().getSimpleName(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SinkUnavailableException("Interrupted while writing to OpenObserve", ex);
		}
		if (response.statusCode() != 200) {
			long retryAfter = response.headers()
				.firstValue("Retry-After")
				.map(OpenObserveClient::seconds)
				.orElse(SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS);
			throw new SinkUnavailableException("OpenObserve answered HTTP " + response.statusCode(), retryAfter, null);
		}
		confirm(response.body(), documents.size());
	}

	private void confirm(String body, int sent) {
		long successful = 0;
		long failed = 0;
		try {
			JsonNode status = json.readTree(body).path("status");
			for (JsonNode item : status) {
				successful += item.path("successful").asLong(0);
				failed += item.path("failed").asLong(0);
			}
		}
		catch (JacksonException ex) {
			throw new SinkUnavailableException("OpenObserve answer is not readable", ex);
		}
		if (failed > 0 || successful != sent) {
			throw new SinkUnavailableException(
					"OpenObserve confirmed " + successful + " of " + sent + " documents (failed " + failed + ")");
		}
	}

	private static long seconds(String value) {
		try {
			return Math.max(0, Long.parseLong(value.strip()));
		}
		catch (NumberFormatException ex) {
			return SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS;
		}
	}

}
