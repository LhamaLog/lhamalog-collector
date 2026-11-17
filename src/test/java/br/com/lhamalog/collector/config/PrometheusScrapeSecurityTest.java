package br.com.lhamalog.collector.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.TestcontainersConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"management.server.port=0",
		"management.endpoints.web.exposure.include=health,info,prometheus",
		"management.prometheus.metrics.export.enabled=true",
		"OTLP_ENABLED=false"
})
class PrometheusScrapeSecurityTest {

	@LocalServerPort
	int serverPort;

	@LocalManagementPort
	int managementPort;

	@Autowired
	MeterRegistry meters;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void prometheusEndpointIsPublicAndContainsLhamalogAndJvmMeters() throws Exception {
		Counter.builder("lhamalog.ingestion.accepted.records")
				.tag("signal", "logs")
				.tag("protocol", "http")
				.register(meters)
				.increment(2);

		HttpResponse<String> health = get("http://localhost:" + managementPort + "/actuator/health");
		assertThat(health.statusCode()).as("health body=%s", health.body()).isEqualTo(200);

		HttpResponse<String> prometheus = get("http://localhost:" + managementPort + "/actuator/prometheus");
		assertThat(prometheus.statusCode()).as("prometheus body=%s", prometheus.body()).isEqualTo(200);
		assertThat(prometheus.body()).contains("lhamalog_ingestion_accepted_records_total");
		assertThat(prometheus.body()).contains("jvm_");
		assertThat(prometheus.body()).contains("lhamalog_queue_size{").contains("lhamalog_dlq_size{");
		assertThat(prometheus.body()).doesNotContain("tenantId=");
	}

	@Test
	void envEndpointIsNotExposedOnManagementPort() throws Exception {
		HttpResponse<String> env = get("http://localhost:" + managementPort + "/actuator/env");
		assertThat(env.statusCode()).isIn(404, 401, 403);
	}

	@Test
	void mainPortRequiresAuthentication() throws Exception {
		HttpResponse<String> response = get("http://localhost:" + serverPort + "/");
		assertThat(response.statusCode()).isIn(401, 403);
	}

	private HttpResponse<String> get(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
