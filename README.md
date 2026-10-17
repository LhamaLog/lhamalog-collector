# lhamalog-collector

Ingestão OTLP (/v1/logs e /v1/traces) com token Bearer por produto; grava logs no Elasticsearch e publica eventos no Redis Streams. Serviço público.

Parte da plataforma LhamaLog (SaaS multi-tenant de análise de logs com IA). Pacote base `br.com.lhamalog.collector`, groupId `br.com.lhamalog`. Gerado no Spring Initializr: Java 25, Spring Boot 4.1.1, Maven. Estrutura proposta: hexagonal leve.

## Pré-requisitos
- JDK 25
- Docker (para a infra local e os testes com Testcontainers)
- make (opcional; Linux/macOS ou WSL)
- Maven não é necessário: use o wrapper `./mvnw`

## Comandos

| Ação | Maven | Makefile |
|---|---|---|
| Rodar | `./mvnw spring-boot:run` | `make run` |
| Buildar | `./mvnw clean package` | `make build` |
| Testar | `./mvnw test` | `make test` |
| Verificação completa | `./mvnw clean verify` | `make verify` |

Executar o jar gerado:

```bash
java -jar target/lhamalog-collector-0.0.1-SNAPSHOT.jar
```

Perfil local: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

## Infra local
MongoDB, Redis e Elasticsearch locais pelo docker-compose da equipe (o mesmo do lhamalog-finder: `docker compose up -d` / `make infra-up`). Os testes usam Testcontainers e sobem os containers sozinhos.

## Dependências
Antes de adicionar qualquer biblioteca, ver Drive da equipe > Arquitetura > Dependências específicas dos módulos (evita bibliotecas com a mesma função).

Dependências a adicionar manualmente (fora do Initializr): io.opentelemetry.proto:opentelemetry-proto, io.grpc (grpc-netty-shaded, grpc-protobuf, grpc-services), com.github.ben-manes.caffeine:caffeine.

Sem GitHub Actions: rode `make verify` localmente antes de cada push.
