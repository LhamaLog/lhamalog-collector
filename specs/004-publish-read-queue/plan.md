# Implementation Plan: Publicar logs na fila de leitura (LLT-184)

**Branch**: `feature/LLT-184-publish-read-queue` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-publish-read-queue/spec.md`

## Summary

Acrescentar retry interno com backoff e DLQ por domínio à publicação já existente no Redis Streams
(`AbstractStreamPublisher` / `TelemetryIngestedPublisher`). Depois de 3 tentativas totais de `XADD` no stream
principal (`lhamalog:stream:logs` / `lhamalog:stream:traces`), o Collector grava o envelope original na DLQ
(`lhamalog:stream:logs:dlq` / `lhamalog:stream:traces:dlq`). Se a DLQ confirmar, o sink trata como sucesso
(exportador 200 / OK); se a DLQ também falhar, permanece `DestinationUnavailable` (503 / `UNAVAILABLE`).
Formato do stream principal inalterado (sem bump de schema). Decisões em [research.md](research.md).

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1.1, Spring Data Redis (Lettuce), Jackson 3, opentelemetry-proto, gRPC

**Storage**: OpenObserve (inalterado); Redis Streams (streams principais + DLQ)

**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers (Redis `redis:8.2.10`)

**Target Platform**: serviço Linux em contêiner; desenvolvimento em Windows

**Project Type**: web-service (receptor OTLP)

**Performance Goals**: com 3 tentativas totais, esperas de 100 e 200 ms (400 ms só com 4 tentativas), retry
total de espera ≤ ~2 s por publicação; um XADD (pipeline) por tentativa

**Constraints**: sem segredo em log/DLQ; Core intocado; não editar `infra/local/.env`; não alterar specs/001 nem
specs/003

**Scale/Scope**: MAXLEN ~ 100000 nos streams principais e nas DLQs; overrides por `LHAMALOG_MESSAGING_*`

## Constitution Check

| Princípio | Situação |
|---|---|
| I. SpecKit em toda feature | spec, plan, research, data-model, contracts, quickstart, tasks e checklist em `specs/004-publish-read-queue` |
| II. IA pode implementar 100% | A IA implementa todas as tasks; decisões em research.md, plan.md e tasks.md; sem marca de autoria por task |
| III. Testes antes do merge | Unitários (retry/DLQ/sucesso) e integração Redis; `mvnw clean verify` local |
| IV. Inglês no código, português na doc | Código e mensagens em inglês; artefatos em pt-BR |
| V. Segredos fora do código | `lastError` só classe+mensagem; overrides só por env; `.env.example` sem segredo |

Sem violações. Revisão depois do design: sem mudanças.

## Project Structure

### Documentation (this feature)

```text
specs/004-publish-read-queue/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── dlq-stream.md
├── checklists/
│   └── requirements.md
├── tasks.md
└── spec.md
```

### Source Code (repository root)

```text
src/main/java/br/com/lhamalog/collector/
├── messaging/
│   ├── AbstractStreamPublisher.java   # retry + DLQ em publish
│   ├── MessagingProperties.java       # tentativas, backoff, teto, MAXLEN DLQ
│   ├── StreamNames.java               # helper de DLQ
│   └── (MessageEnvelope, OutboundMessage, MessagePublishException, MetadataKeys)
├── ingestion/otlp/sink/
│   └── Publishing{Logs,Traces}Sink.java  # javadoc: DLQ ok = sucesso
├── config/OtlpReceiverConfiguration.java

src/test/java/br/com/lhamalog/collector/
├── messaging/{MessagingPropertiesTest, StreamPublisherRedisTest, AbstractStreamPublisherRetryTest}.java
├── ingestion/otlp/application/{PublishingLogsSinkTest, PublishingTracesSinkTest}.java

infra/local/.env.example
src/main/resources/application-{local,prod}.properties
README.md
CHANGELOG.md
```

**Structure Decision**: estender o núcleo `messaging` (LLT-182). Sem pacote novo; sinks só documentam a nova
semântica de sucesso após DLQ (não precisam de lógica extra se `publish` deixar de lançar quando a DLQ ok).

## Complexity Tracking

Sem violações a justificar.