---

description: "Task list for LLT-184 publish retry and DLQ"
---

# Tasks: Publicar logs na fila de leitura (LLT-184)

**Input**: Design documents from `/specs/004-publish-read-queue/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/dlq-stream.md, quickstart.md

**Tests**: Incluídos. A constituição exige teste local antes do merge.

**Organization**: Fundação (config + publisher) antes das histórias. Sem marca de autoria.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Pode correr em paralelo (outro arquivo, sem depender de task incompleta)
- **[Story]**: `[US1]` a `[US3]` só nas fases de história

## Path Conventions

Código em `src/main/java/br/com/lhamalog/collector/`. Testes em `src/test/java/br/com/lhamalog/collector/`.

## Phase 1: Setup (configuráveis)

- [x] T001 [P] Em `infra/local/.env.example`, documentar `LHAMALOG_MESSAGING_PUBLISH_MAX_ATTEMPTS`,
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_INITIAL_MS`, `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_MULTIPLIER`,
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS`, `LHAMALOG_MESSAGING_STREAM_LOGS_DLQ_MAX_LEN`,
  `LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN` (sem editar `infra/local/.env`)
- [x] T002 [P] Em `application-local.properties` e `application-prod.properties`, comentar as novas variáveis
  e padrões; em `README.md`, documentar retry/DLQ e a tabela de env

## Phase 2: Foundational (retry + DLQ no publisher)

- [x] T003 [P] `messaging/StreamNames.java`: helper `dlq(domain)` → `lhamalog:stream:<domain>:dlq`
- [x] T004 `messaging/MessagingProperties.java`: ler tentativas, backoff inicial/multiplicador/teto e
  reutilizar `maxLenFor` para domínio `logs:dlq` / `traces:dlq`; valor inválido falha na subida
- [x] T005 `messaging/AbstractStreamPublisher.java`: em `publish`, retry com backoff injetável (`Sleeper`);
  esgotado → XADD na DLQ com envelope + `targetStream`/`attempts`/`lastError`/`failedAt`; DLQ ok = retorno
  sem exceção; DLQ falha = `MessagePublishException`; logs WARN/INFO/ERROR sem segredo
- [x] T006 [P] Teste `messaging/MessagingPropertiesTest.java` para os novos defaults e overrides
- [x] T007 Teste `messaging/AbstractStreamPublisherRetryTest.java` (Mockito): sucesso na 2ª tentativa; DLQ
  após esgotar; principal+DLQ falham → exceção; backoff respeitado e `lastError` sanitizado

## Phase 3: User Story 1 - Retry recupera sem DLQ (P1)

- [x] T008 [US1] Em `StreamPublisherRedisTest.java` (ou teste dedicado), falha injetada uma vez depois sucesso:
  entrada só no stream principal; DLQ vazia
- [x] T009 [P] [US1] Atualizar Javadoc de `PublishingLogsSink` / `PublishingTracesSink`: retry interno; 503 só
  se DLQ também falhar

## Phase 4: User Story 2 - DLQ e resposta ao exportador (P1)

- [x] T010 [US2] Integração Redis: após falhas persistentes simuladas (ou template que falha no principal e
  escreve na DLQ), conferir envelope original e campos da DLQ (`targetStream`, `attempts`, `lastError`,
  `failedAt`, mesmo `idempotencyId`)
- [x] T011 [P] [US2] `PublishingLogsSinkTest`: publisher que lança → ainda `DestinationUnavailable`; caminho em
  que o publish completa (DLQ ok) não vira 503
- [x] T012 [P] [US2] `PublishingTracesSinkTest`: espelhar o caso de falha total → `DestinationUnavailable`

## Phase 5: User Story 3 - Campos obrigatórios (P2)

- [x] T013 [US3] Confirmar (teste existente ou assert mínimo) que lote sem severidade publica `priority` da
  criticidade `UNSPECIFIED` e que `tenantId`/`productId`/`receivedAt` seguem presentes (já no 182; só
  regressão se necessário)

## Phase 6: Polish

- [x] T014 [P] `CHANGELOG.md`: entrada LLT-184 (retry, DLQ, semântica 200 após DLQ)
- [X] T015 Rodar `.\mvnw.cmd clean verify` (Docker Desktop ligado se Testcontainers precisar) e marcar
  resultados no relatório
- [ ] T016 Métricas de tentativas, sucessos após retry e envios à DLQ (futura; esta entrega registra apenas logs)

## Dependencies

Phase 1 e 2 antes das histórias. US1 e US2 dependem de T005. US3 é regressão do 182. Polish por último.

## Parallel opportunities

T001/T002/T003/T006 em paralelo após o desenho. T011/T012/T014 em paralelo após T005.