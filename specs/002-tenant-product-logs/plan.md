# Implementation Plan: Associar logs ao tenant e ao produto

**Responsável**: michelbocchijr@gmail.com

**Branch**: `feature/LLT-182-tenant-product-logs` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-tenant-product-logs/spec.md`

## Summary

Garantir e testar que todo log aceito leva `tenantId` e `productId` do token e preserva o contexto OTel no
OpenObserve, e publicar uma referência por lote aceito no Redis Stream `lhamalog:stream:logs` como evento
`log.ingested` v2 (traces em `lhamalog:stream:traces`, `trace.ingested` v1), depois da
autenticação, da validação e da gravação no OpenObserve. A publicação usa o núcleo de mensageria da POC
(`AbstractStreamPublisher<T>`, XADD `MAXLEN ~ 100000`). Falha do Redis responde 503 / `UNAVAILABLE`
para nova tentativa; o `idempotencyId` é o `batchId` determinístico do lote. A fila não leva conteúdo nem
atributos OTel: o Finder carrega o lote pelo `lhamalog_batch_id` (revisão D9). Decisões em [research.md](research.md).

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1.1, Spring Data Redis (Lettuce, starter já no projeto), Jackson 3,
opentelemetry-proto, gRPC

**Storage**: OpenObserve (logs guardados, sem mudança); Redis Streams (fila da Varredura, novo uso)

**Testing**: JUnit 5, AssertJ, Testcontainers (Redis `redis:8.2.10`, MongoDB, OpenObserve)

**Target Platform**: serviço Linux em contêiner; desenvolvimento em Windows

**Project Type**: web-service (receptor OTLP)

**Performance Goals**: um XADD por lote (uma entrada de referência), sem ida e volta por log

**Constraints**: timeout de comando do Redis 2 s; nenhum evento sem token válido; sem segredo em arquivo

**Scale/Scope**: stream limitado a ~100000 entradas por padrão, ajustável por ambiente

## Constitution Check

| Princípio | Situação |
|---|---|
| I. SpecKit em toda feature | spec, plan, research, data-model, contracts, quickstart, tasks e checklist em `specs/002-tenant-product-logs` |
| II. IA pode implementar 100% | A IA implementa todas as tasks; decisões em research.md, plan.md e tasks.md; sem marca de autoria por task |
| III. Testes antes do merge | Unitários, integração Redis e aceitação; `mvnw clean verify` local |
| IV. Inglês no código, português na doc | Código e mensagens em inglês; artefatos em pt-BR |
| V. Segredos fora do código | Senha do Redis só por `REDIS_PASSWORD` no `.env` (não versionado); `.env.example` vazio |

Sem violações. Revisão depois do design: sem mudanças.

## Project Structure

### Documentation (this feature)

```text
specs/002-tenant-product-logs/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── log-ingested-stream.md
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
src/main/java/br/com/lhamalog/collector/
├── messaging/                      # núcleo de publicação (da POC)
│   ├── AbstractStreamPublisher.java
│   ├── MessageEnvelope.java
│   ├── OutboundMessage.java
│   ├── MessagePublishException.java
│   ├── MessagingProperties.java
│   ├── MetadataKeys.java
│   └── StreamNames.java
├── ingestion/otlp/sink/
│   ├── LogIngestedBody.java        # corpo do evento
│   ├── LogIngestedPublisher.java   # stream logs, id determinístico
│   └── PublishingLogsSink.java     # OpenObserve, depois Redis
└── config/OtlpReceiverConfiguration.java

src/test/java/br/com/lhamalog/collector/
├── messaging/{MessagingPropertiesTest, StreamPublisherRedisTest}.java
├── ingestion/otlp/sink/LogIngestedPublisherTest.java
├── ingestion/otlp/application/PublishingLogsSinkTest.java
├── ingestion/otlp/ExportLogsAcceptanceTest.java
└── TestcontainersConfiguration.java

infra/local/{compose.yaml, .env.example}
src/main/resources/application-{local,prod}.properties
```

**Structure Decision**: projeto único. O núcleo genérico fica em `messaging` para servir a outros streams;
o que é específico de logs OTLP fica em `ingestion/otlp/sink`, ao lado das conversões já existentes
(`OtlpValues`).

## Complexity Tracking

Sem violações a justificar.
