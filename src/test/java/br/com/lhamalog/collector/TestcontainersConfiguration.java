package br.com.lhamalog.collector;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Mesma tag de infra/local/compose.yaml (LLT-181, ADR_168). */
	static final String OPENOBSERVE_IMAGE = "public.ecr.aws/zinclabs/openobserve:v1.1.2";

	static final int OPENOBSERVE_PORT = 5080;

	/** Credencial gerada a cada execucao; nao existe valor fixo em arquivo. */
	private static final String OPENOBSERVE_USERNAME = "test-" + UUID.randomUUID() + "@lhamalog.local";

	// O OpenObserve exige minuscula, maiuscula, digito e caractere especial (8 a 128).
	private static final String OPENOBSERVE_PASSWORD = "Aa1!" + UUID.randomUUID();

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
	}

	@Bean
	@ServiceConnection
	MongoDBContainer mongoDbContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:latest"));
	}

	/** Fila da Varredura: recebe cada log aceito em lhamalog:stream:logs (LLT-182). Mesma tag do compose local. */
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:8.2.10")).withExposedPorts(6379);
	}

	@Bean
	GenericContainer<?> openObserveContainer() {
		return new GenericContainer<>(DockerImageName.parse(OPENOBSERVE_IMAGE))
			.withExposedPorts(OPENOBSERVE_PORT)
			.withEnv("ZO_DATA_DIR", "/data")
			.withEnv("ZO_ROOT_USER_EMAIL", OPENOBSERVE_USERNAME)
			.withEnv("ZO_ROOT_USER_PASSWORD", OPENOBSERVE_PASSWORD)
			.waitingFor(Wait.forHttp("/healthz").forPort(OPENOBSERVE_PORT).withStartupTimeout(Duration.ofMinutes(2)));
	}

	@Bean
	DynamicPropertyRegistrar openObserveProperties(GenericContainer<?> openObserveContainer) {
		return registry -> {
			registry.add("lhamalog.otlp.openobserve.url", () -> "http://" + openObserveContainer.getHost() + ":"
					+ openObserveContainer.getMappedPort(OPENOBSERVE_PORT));
			registry.add("lhamalog.otlp.openobserve.username", () -> OPENOBSERVE_USERNAME);
			registry.add("lhamalog.otlp.openobserve.password", () -> OPENOBSERVE_PASSWORD);
			// Portas livres nos testes: o perfil local fixa 4328/4327 e o contexto pode subir mais de uma vez.
			registry.add("lhamalog.otlp.http.port", () -> 0);
			registry.add("lhamalog.otlp.grpc.port", () -> 0);
		};
	}

}
