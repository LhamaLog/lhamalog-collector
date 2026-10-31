# Implementation Plan: Recepção OTLP de logs e traces

**Branch**: `001-otlp-receiver` | **Date**: 2026-10-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-otlp-receiver/spec.md`

Pedido técnico deste plano: Java 25, Spring Boot 4.1.1, gRPC e HTTP.
A branch Git atual do repositório é `feature/LLT-181-otlp-receiver`. O nome
`001-otlp-receiver` é o diretório da spec, não um segundo branch.

## Summary

O produto envia logs e traces OTLP por HTTP e por gRPC. O collector aceita o
envio só com um Bearer que identifica um produto, rejeita registro inválido e
só confirma sucesso quando o destino daquele sinal acusou o recebimento.

O caso de uso é único. Dois adaptadores o expõem: HTTP nas portas 4318
(produção) e 4328 (local), gRPC nas portas 4317 e 4327. O token é conferido
por hash na coleção MongoDB `product_tokens` (relacionada a `products` por
`productId`), com cache Caffeine de 30 segundos. Conforme a ADR_168, o
OpenObserve substitui o Elasticsearch para logs e traces: logs aceitos vão para
o stream `logs` e traces aceitos para o stream de traces (padrão `traces`), na
organização `lhamalog`. O `productId` gravado é o do token. O Elasticsearch sai
do collector e o Redis deixa de ser destino de traces. O `tenantId`
(ADR_121/ADR_168) vem do produto do token e é gravado com o `productId` em cada
registro.

A IA implementa toda a feature (constituição 2.0.0). A lógica de validade, de
atribuição e de classe de erro fica isolada em `domain` e `application` para a
revisão do desenvolvedor.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1.1 (Web MVC, Security, MongoDB, Redis,
Validation, Actuator), cliente HTTP do Spring para o OpenObserve,
`opentelemetry-proto` 1.11.1-alpha, gRPC Java 1.85.1 (`grpc-netty-shaded`,
`grpc-protobuf`, `grpc-services`), Caffeine. O starter Elasticsearch sai do
`pom.xml`.

**Storage**: MongoDB coleções `product_tokens` (leitura do token) e `products`
(existência do produto), relação 1:N por `productId`; OpenObserve organização
`lhamalog`, stream `logs` (destino de logs) e stream de traces configurável,
padrão `traces` (destino de traces). Sem Elasticsearch e sem Redis como
destino.

**Testing**: JUnit via Spring Boot, Testcontainers (MongoDB, Redis, que
permanece para a Varredura, e OpenObserve em `GenericContainer`). Comando local: `.\mvnw.cmd test` e
`.\mvnw.cmd clean verify`

**Target Platform**: Serviço Linux (e a máquina do desenvolvedor no perfil
`local`)

**Project Type**: web-service de ingestão, com HTTP e gRPC próprios além da
porta 8080 da aplicação

**Performance Goals**: Um lote dentro do limite de tamanho recebe uma resposta
definitiva na mesma chamada, com o destino local saudável. Sem SLO de produção
nesta feature.

**Constraints**: Corpo máximo padrão de 64 MiB depois da descompressão, igual
nos dois canais. Token e segredo fora do Git. Perfil local não usa 4317/4318,
porque o compose já as ocupa. Mensagens do protocolo OTLP em inglês.
Constituição: no mínimo 40% do código, incluindo a lógica de negócio, escrito
pelo desenvolvedor.

**Scale/Scope**: Um processo atende vários produtos. Sem deduplicação de
reenvio. Métricas, cadastro de token e consulta ficam de fora.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Resultado | Evidência |
|---|---|---|
| I. SpecKit em toda feature | Pass | Este arquivo é o plano da spec `001-otlp-receiver`. Implementação só depois de `tasks`. |
| II. IA pode implementar 100%, com revisão do desenvolvedor | Pass | Research seção 11: a IA implementa tudo seguindo o Spec Kit; lógica de negócio isolada em `domain`/`application` para revisão. Tasks sem marca de autoria. (Constituição 2.0.0, 2026-10-31; a versão 1.0.0 exigia 60/40.) |
| III. Testes antes do merge | Pass | Quickstart exige `.\mvnw.cmd test` e `.\mvnw.cmd clean verify` na máquina local, cobrindo os cenários da spec. |
| IV. Inglês no código, português na documentação | Pass | Plano, spec e contratos explicativos em português. Campos, pacotes e mensagens OTLP em inglês. |
| V. Segredos fora do código e do commit | Pass | Persiste só `tokenHash` em `product_tokens`. Quickstart e testes geram o token na sessão. Nenhum valor de token neste diretório. |
| Stack Java 25, Spring Boot 4.1.1, Maven | Pass | Technical Context e research seção 1. |

### Reavaliação pós-design

Os artefatos de research, modelo, contrato e quickstart usam o mesmo stack e
não introduzem segredo, outro framework ou caminho que pule a spec. Em
2026-10-30 o destino de logs passou de Elasticsearch para OpenObserve e, com a
ADR_168, os traces também saíram do Redis para o OpenObserve. O token passou
para a coleção `product_tokens`. O gate continua passando. Não há violação a justificar.

## Project Structure

### Documentation (this feature)

```text
specs/001-otlp-receiver/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── otlp-export.md
└── tasks.md              # /speckit-tasks, não criado aqui
```

### Source Code (repository root)

```text
src/main/java/br/com/lhamalog/collector/
├── LhamalogCollectorApplication.java
├── config/
│   └── StartupLogger.java
└── ingestion/otlp/
    ├── domain/                 # regras de registro, decisão, produto do token
    ├── application/            # caso de uso único de exportação
    ├── auth/                   # porta de token e adaptador MongoDB + Caffeine
    ├── http/                   # POST /v1/logs e /v1/traces
    ├── grpc/                   # LogsService e TraceService
    └── sink/                   # OpenObserve (logs e traces)

src/test/java/br/com/lhamalog/collector/
├── TestcontainersConfiguration.java
└── ingestion/otlp/             # unidade das regras e integração HTTP/gRPC

src/main/resources/
├── application.properties
├── application-local.properties
└── application-prod.properties
```

**Structure Decision**: Um módulo Maven só, já existente. A ingestão OTLP entra
em `ingestion/otlp` com domínio, caso de uso e adaptadores separados. HTTP e
gRPC não passam pelo Spring MVC da porta 8080. MongoDB, já no `pom.xml`,
segue como leitura de token. O destino de logs e de traces é o OpenObserve, via
HTTP (ADR_168). O Redis continua no `pom.xml` e no compose como fila de
mensagens da Varredura, fora desta feature; não é destino do collector.
A implementação tira o starter, o serviço de compose,
as properties e o Testcontainers do Elasticsearch. Dependências novas: proto
OTLP, gRPC e Caffeine.

## Complexity Tracking

Nenhuma violação da constituição. Esta seção não tem exceção a registrar.
