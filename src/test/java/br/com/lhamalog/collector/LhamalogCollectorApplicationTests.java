package br.com.lhamalog.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
// Portas aleatorias para os receptores OTLP (evita conflito com 4317/4318 do host).
@SpringBootTest(properties = { "lhamalog.otlp.http.port=0", "lhamalog.otlp.grpc.port=0" })
class LhamalogCollectorApplicationTests {

	@Test
	void contextLoads() {
	}

}
