package br.com.lhamalog.collector.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.usage.core.CoreAuthenticationProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpQuotaReservationClientTest {
	private HttpServer server;
	private final AtomicInteger status = new AtomicInteger(201);
	private final AtomicReference<String> serviceToken = new AtomicReference<>();
	private final AtomicReference<String> authorization = new AtomicReference<>();
	private volatile long responseDelayMillis;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/usage-reservations", this::respond);
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void reserveUsesServiceHeaderNeverAuthorizationAndMapsCreated() {
		QuotaReservationClient.ReservationOutcome result = client(Duration.ofSeconds(1)).reserve(request());

		assertThat(result).isEqualTo(QuotaReservationClient.ReservationOutcome.AUTHORIZED);
		assertThat(serviceToken.get()).isEqualTo("service-token");
		assertThat(authorization.get()).isNull();
	}

	@Test
	void reserveMapsConflictAndUnprocessableEntity() {
		status.set(409);
		assertThat(client(Duration.ofSeconds(1)).reserve(request()))
				.isEqualTo(QuotaReservationClient.ReservationOutcome.INSUFFICIENT_BALANCE);
		status.set(422);
		assertThat(client(Duration.ofSeconds(1)).reserve(request()))
				.isEqualTo(QuotaReservationClient.ReservationOutcome.NO_PLAN);
	}

	@Test
	void reserveFailsClosedForServerErrorAndTimeout() {
		status.set(503);
		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).reserve(request()))
				.isInstanceOf(QuotaReservationClient.UnavailableException.class);
		status.set(201);
		responseDelayMillis = 250;
		assertThatThrownBy(() -> client(Duration.ofMillis(25)).reserve(request()))
				.isInstanceOf(QuotaReservationClient.UnavailableException.class);
	}

	@Test
	void refundReturnsCoreStatusAndUsesOnlyServiceHeader() {
		status.set(409);
		assertThat(client(Duration.ofSeconds(1)).refund("reservation-1")).isEqualTo(409);
		assertThat(serviceToken.get()).isEqualTo("service-token");
		assertThat(authorization.get()).isNull();
	}

	@Test
	void confirmSucceedsOnNoContentAndFailsClosedOnConflict() {
		status.set(204);
		client(Duration.ofSeconds(1)).confirm("reservation-1");
		assertThat(serviceToken.get()).isEqualTo("service-token");
		assertThat(authorization.get()).isNull();

		status.set(409);
		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).confirm("reservation-1"))
				.isInstanceOf(QuotaReservationClient.UnavailableException.class);
	}

	@Test
	void releaseIgnoresConflictAndUsesServiceHeader() {
		status.set(409);
		client(Duration.ofSeconds(1)).release("reservation-1");
		assertThat(serviceToken.get()).isEqualTo("service-token");
		assertThat(authorization.get()).isNull();
	}

	private HttpQuotaReservationClient client(Duration timeout) {
		return new HttpQuotaReservationClient(HttpClient.newHttpClient(),
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()), new CoreAuthenticationProvider("service-token"),
				JsonMapper.builder().build(), timeout);
	}

	private static QuotaReservationClient.ReservationRequest request() {
		return new QuotaReservationClient.ReservationRequest("reservation-1", "tenant", "product", 1, "batch",
				br.com.lhamalog.collector.usage.domain.UsageDomain.LOGS);
	}

	private void respond(HttpExchange exchange) throws java.io.IOException {
		serviceToken.set(exchange.getRequestHeaders().getFirst("X-LhamaLog-Service-Token"));
		authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
		if (responseDelayMillis > 0) {
			try {
				Thread.sleep(responseDelayMillis);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] body = exchange.getRequestURI().getPath().endsWith("/refund") ? new byte[0]
				: "{\"outcome\":\"AUTHORIZED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status.get(), body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}
}
