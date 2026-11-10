package br.com.lhamalog.collector.usage.core;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;

public final class CoreUsageFactsClient implements UsageFactsClient {

	private final HttpClient http;
	private final URI endpoint;
	private final CoreAuthenticationProvider authentication;

	public CoreUsageFactsClient(HttpClient http, URI coreUrl, CoreAuthenticationProvider authentication) {
		this.http = http;
		this.endpoint = coreUrl.resolve("/internal/usage-facts");
		this.authentication = authentication;
	}

	@Override
	public int send(UsageFact fact) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.header("X-LhamaLog-Service-Token", authentication.serviceToken())
			.POST(HttpRequest.BodyPublishers.ofString(toJson(fact)))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private static String toJson(UsageFact fact) {
		String records = fact.acceptedRecordIds().stream().map(CoreUsageFactsClient::quote)
			.collect(Collectors.joining(","));
		return "{\"usageFactId\":" + quote(fact.usageFactId()) + ",\"batchId\":" + quote(fact.batchId())
				+ ",\"tenantId\":" + quote(fact.tenantId()) + ",\"productId\":" + quote(fact.productId())
				+ ",\"domain\":" + quote(fact.domain().name()) + ",\"acceptedRecordIds\":[" + records
				+ "],\"acceptedRecordCount\":" + fact.acceptedRecordCount() + ",\"acceptedBytes\":"
				+ fact.acceptedBytes() + ",\"acceptedAt\":" + quote(fact.acceptedAt().toString())
				+ ",\"schemaVersion\":1}";
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
