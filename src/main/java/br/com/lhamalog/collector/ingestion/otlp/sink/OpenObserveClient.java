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
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes JSON documents to OpenObserve with {@code POST /api/{organization}/{stream}/_json} (ADR_168).
 *
 * <p>A write is confirmed only when the answer is HTTP 200, the sum of {@code failed} is zero and the sum
 * of {@code successful} equals the number of documents sent. The other answers are split by cause (T040,
 * FR-020):
 *
 * <ul>
 * <li>invalid data, not retryable: HTTP 400, 413, 415 or 422, or HTTP 200 whose
 * {@code failed} is above zero while {@code successful + failed} equals the number sent. Throws
 * {@link SinkRejectedException} with the refused and stored counts;</li>
 * <li>unavailable, retryable: 5xx, any other status (401/403 credential, 404 organization, 408, 429), a
 * timeout, a network error, an unreadable answer or counts that do not add up. Throws
 * {@link SinkUnavailableException}.</li>
 * </ul>
 *
 * <p>Basic auth comes from the environment and the credential is never logged.
 */
public class OpenObserveClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Statuses in which OpenObserve refuses the content itself; retrying the same body cannot help. */
	private static final Set<Integer> DATA_ERROR_STATUSES = Set.of(400, 413, 415, 422);

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

	public String organization() {
		return organization;
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
		if (DATA_ERROR_STATUSES.contains(response.statusCode())) {
			throw new SinkRejectedException(documents.size(), 0,
					"OpenObserve refused the documents with HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
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
		if (failed == 0 && successful == sent) {
			return;
		}
		if (failed > 0 && successful + failed == sent) {
			throw new SinkRejectedException((int) failed, (int) successful,
					"OpenObserve refused " + failed + " of " + sent + " documents: " + abbreviate(body));
		}
		throw new SinkUnavailableException(
				"OpenObserve confirmed " + successful + " of " + sent + " documents (failed " + failed + ")");
	}

	private static String abbreviate(String body) {
		if (body == null) {
			return "";
		}
		String flat = body.replaceAll("\\s+", " ").strip();
		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
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
