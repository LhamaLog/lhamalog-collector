package br.com.lhamalog.collector.quota;

import br.com.lhamalog.collector.logging.CorrelationId;
import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Synchronous Core reservation adapter. All transport ambiguity is deliberately fail-closed. */
public final class HttpQuotaReservationClient implements QuotaReservationClient {

	private static final Logger log = LoggerFactory.getLogger(HttpQuotaReservationClient.class);
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
	public ReservationResult reserve(ReservationRequest request) {
		try {
			HttpResponse<String> response = http.send(withCorrelation(HttpRequest.newBuilder(endpoint).timeout(timeout)
					.header("Content-Type", "application/json")
					.header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))).build(),
					HttpResponse.BodyHandlers.ofString());
			return switch (response.statusCode()) {
				case 200, 201 -> parseResult(response.body());
				case 403 -> parseSuspendedOrUnavailable(response.body());
				case 409 -> ReservationResult.of(ReservationOutcome.INSUFFICIENT_BALANCE);
				case 422 -> ReservationResult.of(ReservationOutcome.NO_PLAN);
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
			HttpResponse<String> response = http.send(withCorrelation(HttpRequest
					.newBuilder(transitionEndpoint(reservationId, "refund")).timeout(timeout)
					.header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.noBody())).build(), HttpResponse.BodyHandlers.ofString());
			return response.statusCode();
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new UnavailableException("Core refund is unavailable", exception);
		}
	}

	private void callTransition(String reservationId, String operation, boolean ignoreConflict) {
		try {
			HttpResponse<String> response = http.send(withCorrelation(HttpRequest
					.newBuilder(transitionEndpoint(reservationId, operation)).timeout(timeout)
					.header("X-LhamaLog-Service-Token", authentication.serviceToken())
					.POST(HttpRequest.BodyPublishers.noBody())).build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 204 || (ignoreConflict && response.statusCode() == 409)) return;
			throw new UnavailableException("Core " + operation + " returned " + response.statusCode());
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
			throw new UnavailableException("Core " + operation + " is unavailable", exception);
		}
	}

	/** Adds {@code X-Correlation-Id} when the current MDC value is present. */
	private static HttpRequest.Builder withCorrelation(HttpRequest.Builder builder) {
		String correlationId = CorrelationId.current();
		if (correlationId != null && !correlationId.isBlank()) {
			builder.header(CorrelationId.HEADER, correlationId);
		}
		return builder;
	}

	private URI transitionEndpoint(String reservationId, String operation) {
		return URI.create(endpoint.toString() + "/" + reservationId + "/" + operation);
	}

	/**
	 * Parses {@code outcome} and optional {@code retentionDays} from Core JSON. Extra fields such as
	 * {@code overageBytes} are ignored; only known {@link ReservationOutcome} names are accepted.
	 * Absent/null/{@code <=0} retentionDays means unknown (LLT-185). Non-integral (e.g. {@code 7.5}) or
	 * out-of-range values are rejected as unknown with a WARN, never rounded: rounding up would keep data longer
	 * than the plan and rounding down could delete data the tenant paid for.
	 */
	private ReservationResult parseResult(String body) {
		try {
			var tree = json.readTree(body);
			String outcome = tree.path("outcome").asString();
			if (outcome == null || outcome.isBlank()) {
				throw new UnavailableException("Core reservation response is invalid");
			}
			Integer retentionDays = null;
			var retentionNode = tree.path("retentionDays");
			if (!retentionNode.isMissingNode() && !retentionNode.isNull()) {
				if (retentionNode.isIntegralNumber() && retentionNode.canConvertToInt()) {
					int value = retentionNode.asInt();
					if (value > 0) {
						retentionDays = value;
					}
				}
				else {
					log.warn(
							"event=quota.reservation.retention_invalid | Core retentionDays is not a positive integer; treated as unknown | retentionDays={}",
							retentionNode.toString());
				}
			}
			return new ReservationResult(ReservationOutcome.valueOf(outcome), retentionDays);
		}
		catch (JacksonException | IllegalArgumentException exception) {
			throw new UnavailableException("Core reservation response is invalid", exception);
		}
	}

	/**
	 * Core emits HTTP 403 with {@code {"outcome":"SUSPENDED",...}} for suspended subscriptions.
	 * Any other 403 (or unparseable body) stays fail-closed as {@link UnavailableException}.
	 */
	private ReservationResult parseSuspendedOrUnavailable(String body) {
		try {
			ReservationResult result = parseResult(body);
			if (ReservationOutcome.SUSPENDED == result.outcome()) {
				return result;
			}
		}
		catch (UnavailableException ignored) {
			// fall through to generic 403 unavailable
		}
		throw new UnavailableException("Core reservation returned 403");
	}
}
