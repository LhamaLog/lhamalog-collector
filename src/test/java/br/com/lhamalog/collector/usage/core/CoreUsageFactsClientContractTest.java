package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoreUsageFactsClientContractTest {

	@Test
	void postsOnlyTheUsageFactWithTheDedicatedServiceToken() throws Exception {
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> idempotency = new AtomicReference<>();
		AtomicReference<String> token = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/internal/usage-facts", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			token.set(exchange.getRequestHeaders().getFirst("X-LhamaLog-Service-Token"));
			body.set(new String(exchange.getRequestBody().readAllBytes()));
			exchange.sendResponseHeaders(201, -1);
			exchange.close();
		});
		server.start();
		try {
			CoreUsageFactsClient client = new CoreUsageFactsClient(HttpClient.newHttpClient(),
					URI.create("http://localhost:" + server.getAddress().getPort()), new CoreAuthenticationProvider("test-token"));
			UsageFact fact = UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of("record"), 99,
					Instant.parse("2026-11-09T00:00:00Z"));

			assertThat(client.send(fact)).isEqualTo(201);
			assertThat(token.get()).isEqualTo("test-token");
			assertThat(authorization.get()).isNull();
			assertThat(idempotency.get()).isNull();
			assertThat(body.get()).contains("\"usageFactId\":\"" + fact.usageFactId() + "\"")
				.contains("\"acceptedBytes\":99").doesNotContain("otlp");
		}
		finally {
			server.stop(0);
		}
	}
}
