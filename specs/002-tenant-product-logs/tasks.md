---

description: "Task list for tenant/product log association and log.ingested publishing"
---

# Tasks: Associar logs ao tenant e ao produto

**Input**: Design documents from `/specs/002-tenant-product-logs/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/log-ingested-stream.md, quickstart.md

**Tests**: Incluídos. A constituição exige teste local antes do merge.

**Organization**: Uma fase por história. Sem marca de autoria: a IA implementa todas as tasks e o
desenvolvedor revisa (constituição 2.0.0, princípio II).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Pode correr em paralelo (outro arquivo, sem depender de task incompleta)
- **[Story]**: `[US1]` a `[US4]` só nas fases de história

## Path Conventions

Código em `src/main/java/br/com/lhamalog/collector/`. Testes em `src/test/java/br/com/lhamalog/collector/`.

## Phase 1: Setup

- [x] T001 [P] Em `infra/local/compose.yaml`, exigir senha no Redis com `--requirepass ${REDIS_PASSWORD:?...}` e healthcheck autenticado por `REDISCLI_AUTH` no ambiente do contêiner
- [x] T002 [P] Em `infra/local/.env.example`, adicionar `REDIS_PASSWORD=` vazio e descrever o Redis como fila que o collector publica
- [x] T003 [P] Em `src/main/resources/application-local.properties`, adicionar `spring.data.redis.password`, `timeout` e `connect-timeout` (2 s); em `application-prod.properties`, timeouts e a lista de variáveis
- [x] T004 [P] Em `TestcontainersConfiguration.java`, fixar `redis:8.2.10` (a tag do compose) e atualizar o comentário

## Phase 2: Foundational

- [x] T005 [P] `messaging/MetadataKeys.java`, `StreamNames.java`, `MessageEnvelope.java`, `OutboundMessage.java` (tenantId obrigatório), `MessagePublishException.java`
- [x] T006 [P] `messaging/MessagingProperties.java`: padrão 100000, override `LHAMALOG_MESSAGING_STREAM_<DOMINIO>_MAX_LEN`, valor inválido falha na subida
- [x] T007 `messaging/AbstractStreamPublisher.java`: `publish` final, envelope, XADD `MAXLEN ~` em pipeline, falha do Redis vira `MessagePublishException`
- [x] T008 [P] Teste `messaging/MessagingPropertiesTest.java`
- [x] T009 Teste `messaging/StreamPublisherRedisTest.java` (Testcontainers): campos da entrada, `MAXLEN ~`, Redis fora

## Phase 3: User Story 1 - Log com tenant e produto e contexto OTel (P1)

- [x] T010 [US1] `ingestion/otlp/sink/LogIngestedBody.java`: corpo com log, recurso e escopo, sem `tenantId`
- [x] T011 [US1] `ingestion/otlp/sink/LogIngestedPublisher.java`: stream `logs`, evento `log.ingested`, origem `collector`, metadados com tenant/produto do token, `correlationId` e `traceId`
- [x] T012 [P] [US1] Teste `ingestion/otlp/sink/LogIngestedPublisherTest.java`: metadados, preservação de recurso/escopo/atributos, corpo sem tenant/produto no topo
- [x] T013 [US1] Em `ExportLogsAcceptanceTest.java`, conferir no stream tenant/produto do token, `service.name`, atributos de recurso e de log, com atributos falsos no lote

## Phase 4: User Story 2 - Publicação após aceite (P1)

- [x] T014 [US2] `ingestion/otlp/sink/PublishingLogsSink.java`: grava no OpenObserve e depois publica; nada publicado se o OpenObserve falha ou recusa
- [x] T015 [US2] Em `config/OtlpReceiverConfiguration.java`, criar o bean `LogIngestedPublisher` e envolver o `OpenObserveLogsSink`
- [x] T016 [US2] `idempotencyId` determinístico (research D5) e teste de reenvio e de duplicatas em `LogIngestedPublisherTest.java`

## Phase 5: User Story 3 - Nada sem token válido ou inválido na fila (P1)

- [x] T017 [P] [US3] `ingestion/otlp/application/PublishingLogsSinkTest.java`: não autorizado, catálogo fora e lote inválido não publicam; lote misto publica só os válidos
- [x] T018 [US3] Em `ExportLogsAcceptanceTest.java`, token revogado/desconhecido e lote todo inválido sem entrada no stream

## Phase 6: User Story 4 - Fila fora pede nova tentativa (P2)

- [x] T019 [US4] Em `PublishingLogsSink.java`, `MessagePublishException` vira `SinkUnavailableException` (503 / `UNAVAILABLE`, Retry-After 5 s)
- [x] T020 [P] [US4] Teste em `PublishingLogsSinkTest.java` do Redis fora como `DestinationUnavailable`

## Phase 7: Polish

- [x] T021 [P] `README.md`: Redis como fila de logs aceitos e `REDIS_PASSWORD` obrigatório no compose
- [x] T022 Rodar `.\mvnw.cmd clean verify` e o roteiro de `quickstart.md`

## Phase 8: Referência ao OpenObserve (revisão 2026-11-02)

- [x] T023 Specs: spec FR-003/004/011/012, research D9-D12, data-model, contrato `log-ingested-stream.md`
- [x] T024 `RecordIds`, `StoredBatch`, `BatchStore`, `Signal`: `OpenObserveLogsSink`/`OpenObserveTracesSink` gravam `lhamalog_batch_id` e `lhamalog_record_id` e devolvem a faixa de `_timestamp`
- [x] T025 `Criticality` (só severidade) e `TelemetryIngestedBody`; `LogIngestedBody` removido
- [x] T026 `TelemetryIngestedPublisher`, `LogIngestedPublisher` (v2) e `TraceIngestedPublisher` (`lhamalog:stream:traces`); `PublishingTracesSink` e beans
- [x] T027 Testes: `TelemetryIngestedPublisherTest`, `OpenObserveSinkTest`, `PublishingLogsSinkTest`, aceite de logs e traces (referência localiza o lote; sem conteúdo)
- [x] T028 Rodar `.\mvnw.cmd test`

## Dependencies

Phase 1 e 2 antes das histórias. US1 antes de US2 (o publicador existe). US3 e US4 dependem do
`PublishingLogsSink` (T014).
