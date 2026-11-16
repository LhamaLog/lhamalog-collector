package br.com.lhamalog.collector.quota;

import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Synchronous Core reservation adapter. All transport ambiguity is deliberately fail-closed. */
public final class HttpQuotaReservationClient implements QuotaReservationClient {
	private final HttpClient http;
	private final URI endpoint;
	private final CoreAuthenticationProvider authentication;
	private final JsonMapper json;
	private final Duration timeout;

	public HttpQuotaReservationClient(HttpClient http, URI coreUrl, CoreAuthenticationProvider authentication,
			JsonMapper json, Duration timeout) {
		this.http = http;
		this.endpoint = coreUrl.resolve("/internal/usage-reservations");
		this.authentication = authentication;
		this.json = json;
		this.timeout = timeout;
	}

	@Override
	public ReservationOutcome reserve(ReservationRequest request) {
		try {
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
					.header("Content-Type", "application/json")
					.header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString());
			return switch (response.statusCode()) {
				case 200, 201 -> parseOutcome(response.body());
				case 409 -> ReservationOutcome.INSUFFICIENT_BALANCE;
				case 422 -> ReservationOutcome.NO_PLAN;
				default -> throw new UnavailableException("Core reservation returned " + response.statusCode());
			};
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new UnavailableException("Core reservation is unavailable", exception);
		}
	}

	@Override
	public void confirm(String reservationId) {
		callTransition(reservationId, "confirm", false);
	}

	@Override
	public void release(String reservationId) {
		callTransition(reservationId, "release", true);
	}

	@Override
	public int refund(String reservationId) {
		try {
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(transitionEndpoint(reservationId, "refund"))
					.timeout(timeout).header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
			return response.statusCode();
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new UnavailableException("Core refund is unavailable", exception);
		}
	}

	private void callTransition(String reservationId, String operation, boolean ignoreConflict) {
		try {
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(transitionEndpoint(reservationId, operation))
					.timeout(timeout).header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 204 || (ignoreConflict && response.statusCode() == 409)) return;
			throw new UnavailableException("Core " + operation + " returned " + response.statusCode());
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new UnavailableException("Core " + operation + " is unavailable", exception);
		}
	}

	private URI transitionEndpoint(String reservationId, String operation) {
		return URI.create(endpoint.toString() + "/" + reservationId + "/" + operation);
	}

	private ReservationOutcome parseOutcome(String body) {
		try {
			String outcome = json.readTree(body).get("outcome").asText();
			return ReservationOutcome.valueOf(outcome);
		}
		catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
			throw new UnavailableException("Core reservation response is invalid", exception);
		}
	}
}
