# Tarefas: Limites de taxa e tamanho de lote (LLT-183)

**Entrada**: artefatos em `specs/007-plan-rate-and-size-limits/`  
**Pré-requisitos**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, contratos e `quickstart.md`.

**Testes**: obrigatórios pela Constituição III — criar testes **antes** da implementação em cada história.

**Tags**: cada tarefa indica **Collector** ou **Core**. Caminhos Core usam `../lhamalog-core-llt183/`.

**Bloqueios**: nenhum. Q13/Q14 respondidas pelo Michel em 16/11/2026 (sessão 3) → T042/T044 feitas. D4/D5 resolvidos → T041/T043 feitos na Phase C1b.

## Fase 1: Setup

**Objetivo**: propriedades e esqueleto de pacotes sem lógica de negócio completa.

- [X] T001 **Collector** Adicionar `lhamalog.otlp.max-batch-size` (default 5MB) e documentação de propriedade em `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverProperties.java` e `src/main/resources/application.properties`
- [X] T002 **Collector** Criar `RateLimitProperties` (`requests-per-second` default 20, `retry-after` default PROVISÓRIO 300s, política Redis-down) em `src/main/java/br/com/lhamalog/collector/config/RateLimitProperties.java`
- [X] T003 **Collector** Criar pacote esqueleto `src/main/java/br/com/lhamalog/collector/ingestion/otlp/ratelimit/` (portas vazias/interfaces)
- [X] T004 **Core** Criar enum `SubscriptionStatus` (ATIVA, SUSPENSA, INADIMPLENTE) em `../lhamalog-core-llt183/src/main/java/br/com/lhamalog/core/usage/application/SubscriptionStatus.java`

---

## Fase 2: Fundação (decisões, props de Retry-After Core, contratos base)

**Objetivo**: tipos de decisão e Retry-After 300 no caminho indisponível; base compartilhada antes das histórias.

- [X] T005 [P] **Collector** Criar testes de propriedades `max-batch-size`, rate-limit e Retry-After em `src/test/java/br/com/lhamalog/collector/config/`
- [X] T006 [P] **Collector** Criar testes de novos outcomes (`RATE_LIMITED`, `BATCH_TOO_LARGE`, `SUSPENDED`) em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/`
- [X] T007 **Collector** Estender `IngestionDecision` / `QuotaGate.Outcome` (ou equivalentes) com `RATE_LIMITED`, `BATCH_TOO_LARGE`, `SUSPENDED` em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/`
- [X] T008 **Collector** Alterar Retry-After do caminho `QuotaUnavailable`/Core fora/sem plano de 120 para `lhamalog.quota.unavailable-retry-after` default **300 s** (D4), mantendo saldo insuficiente em **120**, em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpStatusMapper.java`, `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java` e `QuotaProperties` (ou equivalente)
- [X] T009 **Collector** Atualizar testes existentes de 503/UNAVAILABLE que assumem Retry-After 120 no caminho Core fora para 300 em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`

**Checkpoint**: fundação de outcomes e Retry-After Core 300 pronta. D4 “até 5 vezes” permanece só em T043 (bloqueada).

---

## Fase 3: US1 — Rate limit 20 req/s (P1) 🎯 MVP

**Objetivo**: recusar acima de 20 req/s por tenant com 429 / RESOURCE_EXHAUSTED antes de tamanho e reserva.

**Teste independente**: ≤20 aceita taxa; >20 → 429 + Retry-After da propriedade; 0 chamadas reserve; logs+traces compartilham contador.

### Testes

- [X] T010 [P] [US1] **Collector** Criar testes unitários do contador Redis/Lua (janela 1s, chave `lhamalog:ratelimit:{tenantId}:{epochSecond}`, TTL 2s, limite 20) em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/ratelimit/`
- [X] T011 [P] [US1] **Collector** Criar testes OTLP HTTP protobuf/JSON e gRPC de taxa excedida (429 / RESOURCE_EXHAUSTED + trailer) em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`
- [X] T012 [P] [US1] **Collector** Criar teste de que rate check ocorre após auth e antes de `OtlpPayload.read()` / reserva em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/`

### Implementação

- [X] T013 [US1] **Collector** Implementar `RedisRateLimiter` (Lua INCR+EXPIRE) em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/ratelimit/`
- [X] T014 [US1] **Collector** Integrar rate check em `BatchIngestion.java` após auth e antes de body/size/quota
- [X] T015 [US1] **Collector** Mapear `RATE_LIMITED` → HTTP 429 / gRPC RESOURCE_EXHAUSTED + Retry-After de `RateLimitProperties` em `OtlpHttpStatusMapper.java` e `OtlpGrpcServer.java`
- [X] T016 [US1] **Collector** Documentar e aplicar default **PROVISÓRIO** `lhamalog.rate-limit.retry-after=300` em `RateLimitProperties` (valor final = T041 / D5)
- [X] T017 [US1] **Collector** Implementar política **provisória** Redis-down (fail-open + métrica/WARN, configurável) em `ratelimit/` marcada [A CONFIRMAR Q13]; valor final = T042

**Checkpoint**: rate limit operacional com defaults provisórios D5/Q13.

---

## Fase 4: US2 — Tamanho máximo 5 MB (P1)

**Objetivo**: recusar lote > 5 MB com 413 / RESOURCE_EXHAUSTED após taxa e antes da reserva; manter teto 64 MB.

**Teste independente**: lote >5MB e <64MB → 413 sem reserve; lote ≤5MB passa tamanho.

### Testes

- [X] T018 [P] [US2] **Collector** Criar testes de `max-batch-size` (descomprimido) vs `max-message-size` em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/` e/ou `.../http/`
- [X] T019 [P] [US2] **Collector** Criar testes OTLP HTTP/gRPC de recusa 413 / RESOURCE_EXHAUSTED por lote de aplicação em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`

### Implementação

- [X] T020 [US2] **Collector** Aplicar checagem de 5 MB (payload decodificado/descomprimido) após rate e antes da reserva em `OtlpPayload.java` / `BatchIngestion.java` (HTTP) e após auth+rate no caminho gRPC (`OtlpGrpcServer` / use case)
- [X] T021 [US2] **Collector** Mapear `BATCH_TOO_LARGE` → 413 / RESOURCE_EXHAUSTED em `OtlpHttpStatusMapper.java` e `OtlpGrpcServer.java`

**Checkpoint**: tamanho de aplicação ativo sem confundir com teto 64 MB.

---

## Fase 5: US3 — Ordem taxa → tamanho → reserva (P2)

**Objetivo**: garantir ordem D8 e ausência de reserve em recusas locais.

**Teste independente**: taxa esgotada ou lote grande → 0 chamadas `HttpQuotaReservationClient.reserve`.

### Testes

- [X] T022 [P] [US3] **Collector** Criar testes de ordem e de zero chamadas de reserva em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/BatchIngestion*Test.java`
- [X] T023 [P] [US3] **Collector** Criar teste combinado taxa ok + tamanho ok + saldo insuficiente → 429 Retry-After 120 em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`

### Implementação

- [X] T024 [US3] **Collector** Ajustar pipeline em `BatchIngestion.java` para ordem canônica auth → rate → size → assess/quota e falhar cedo

**Checkpoint**: ordem D8 coberta por testes.

---

## Fase 6: US4 — SUSPENSA e overageEnabled (P2)

**Objetivo**: Core status/overage; Collector 403; overage autoriza além do saldo sem cobrança.

**Teste independente**: SUSPENSA → 403; overage true + saldo baixo → autorizado com excedente; overage false → 409/429.

### Testes

- [X] T025 [P] [US4] **Core** Criar testes de `PlanSubscription` defaults ATIVA/`overageEnabled=false` e status SUSPENSA em `../lhamalog-core-llt183/src/test/java/br/com/lhamalog/core/usage/application/`
- [X] T026 [P] [US4] **Core** Criar testes de `QuotaReservationService` para SUSPENDED e AUTHORIZED+overageBytes em `../lhamalog-core-llt183/src/test/java/br/com/lhamalog/core/usage/application/QuotaReservationServiceTest.java`
- [X] T027 [P] [US4] **Core** Criar testes de contrato HTTP 403 `SUSPENDED` e overage em `../lhamalog-core-llt183/src/test/java/br/com/lhamalog/core/usage/api/UsageReservationsControllerContractTest.java`
- [X] T028 [P] [US4] **Collector** Criar testes de cliente mapeando 403 → SUSPENDED (não UNAVAILABLE) em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/quota/` ou `.../usage/`
- [X] T029 [P] [US4] **Collector** Criar testes OTLP 403 / PERMISSION_DENIED para suspensão em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`

### Implementação

- [X] T030 [US4] **Core** Estender `PlanSubscription.java` com `status` e `overageEnabled` (defaults legados ATIVA/false) em `../lhamalog-core-llt183/src/main/java/br/com/lhamalog/core/usage/application/PlanSubscription.java`
- [X] T031 [US4] **Core** Implementar ramo SUSPENSA → outcome SUSPENDED e overage (registrar `overageBytes`) em `../lhamalog-core-llt183/src/main/java/br/com/lhamalog/core/usage/application/QuotaReservationService.java`
- [X] T032 [US4] **Core** Mapear SUSPENDED → HTTP 403 no `UsageReservationsController.java`
- [X] T033 [US4] **Collector** Mapear 403/SUSPENDED em `HttpQuotaReservationClient.java` e `CanonicalQuotaGate.java`
- [X] T034 [US4] **Collector** Mapear `SUSPENDED` → HTTP 403 / gRPC PERMISSION_DENIED em `OtlpHttpStatusMapper.java` e `OtlpGrpcServer.java`
- [X] T035 [US4] **Core** Tratar `INADIMPLENTE` como ATIVA **provisória** na reserva (log/métrica de status) em `QuotaReservationService.java` até Q14; não inventar bloqueio 403 (ajuste final = T044)

**Checkpoint**: SUSPENSA e overage cobertos; INADIMPLENTE provisório.

---

## Fase 7: US5 — Observabilidade (P3)

**Objetivo**: métricas/logs distinguindo taxa, tamanho, saldo, suspensão, Core fora, Redis-down.

**Teste independente**: cada motivo emite label/campo distinto.

### Testes

- [X] T036 [P] [US5] **Collector** Criar testes de métricas/logs sanitizados por motivo em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`

### Implementação

- [X] T037 [US5] **Collector** Emitir métricas/logs com `reason` distinto (rate_limit, batch_too_large, insufficient_balance, subscription_suspended, quota_unavailable, rate_limit_store_unavailable) nos pontos de recusa

**Checkpoint**: SC-004 atendido sem alerta in-app.

---

## Fase 8: Polimento e confirmações

- [X] T038 [P] **Collector** Executar cenários de `quickstart.md` (taxa, tamanho, ordem, SUSPENSA, overage, Core fora) e registrar evidência em `specs/007-plan-rate-and-size-limits/` — feito em 16/11/2026 08:44 (BRT) na stack local (`docker compose -p llt183-e2e`, OpenObserve v1.1.2) com jars do HEAD 908dfe1: evidência em `e2e-llt183-evidence.md` (taxa, tamanho, saldo, excedente, SUSPENSA/INADIMPLENTE, sem plano, Core fora 6ª→500, persistência, Redis fora, correlation id Collector→Core, sem vazamento de segredos). Limitação: `/actuator/metrics` responde 401 (Spring Security) — leitura de métricas fica para a LLT-187.
- [X] T039 [P] **Collector** / **Core** `mvnw.cmd verify` em 16/11/2026 01:48 (BRT): Collector **292 testes, 0 falhas**; Core **99 testes, 1 falha** conhecida e pré-existente da LLT-175 (`CompanyRegistrationHttpTests.adminDeletesRegistrationAndMemberCannot`), aceita pelo Michel; detalhes em `progress-notes.md`
- [X] T040 [P] **Collector** Atualizar docs de runbook/usage se existirem em `docs/` (sem segredos; pt-BR) — feito em 16/11/2026: `docs/rate-and-size-limits-runbook.md` (novo) e `docs/quota-reservation-runbook.md` (Core fora: Retry-After 300, 5 tentativas, 6ª→500/INTERNAL). `docs/usage-measurement.md` ainda cita textos de log antigos (acompanhamento menor).
- [X] T041 **Collector** D5 confirmado (16/11 sessão 2): `retry-after=5s`; violações 5/60s → bloqueio 5 min com `block-retry-after=300s`; Lua atômica; métricas `rate_limit` / `rate_limit_blocked`; testes Redis + HTTP/gRPC atualizados
- [X] T042 **Collector** Q13 confirmado (16/11 sessão 3): fail-open com WARN `event=ingestion.rate_limit.store_unavailable` e métrica — comportamento de T017 mantido, sem mudança de código
- [X] T043 **Collector** D4 confirmado (16/11 sessão 2): orçamento 5 tentativas/lote via `reservationId` determinístico; Redis `core-unavailable:{batchKey}`; 6ª → 500/INTERNAL; NO_PLAN conta; Redis erro → 503/300 sem orçamento; testes BatchIngestion + Redis tracker
- [X] T044 **Core** Q14 confirmado (16/11 sessão 3): `PlanSubscription.delinquentSince` + `lhamalog.subscription.delinquency-grace` (default 15d, `SubscriptionProperties`); INADIMPLENTE dentro da carência → permitido (INFO `subscription.delinquent.in_grace`); a partir do fim → SUSPENDED/403 (WARN `subscription.delinquent.grace_expired`); sem data → permitido com WARN `subscription.delinquent.missing_since`; `Clock` injetável; testes em `PlanSubscriptionTest`, `SubscriptionPropertiesTest`, `QuotaReservationServiceTest`

### Padrão de logs (decidido pelo Michel em 16/11/2026; rascunho `ADR-padrao-de-logs-v1`; constituição 2.1.0, princípio VI)

- [X] T045 **Collector** Aplicar o padrão no escopo da LLT-183: `logging.pattern.*` com data ISO-8601 + fuso, `cid` e `classe.método`; `logging/CorrelationId` (+ `propagate` para o executor de reserva/sanitização); `X-Correlation-Id` no OTLP/HTTP; `CorrelationIdServerInterceptor` no OTLP/gRPC; `HttpQuotaReservationClient` envia `X-Correlation-Id` ao Core; logs da LLT-183 no formato `event=<código> | aviso | k=v`; testes `CorrelationIdTest`, `OtlpHttpCorrelationIdTest`, `CorrelationIdServerInterceptorTest`, `HttpQuotaReservationClientTest`
- [X] T046 **Core** Aplicar o padrão no escopo da LLT-183: `logging.pattern.*`; `logging/CorrelationIdFilter` (maior precedência, antes do Spring Security) com `X-Correlation-Id` entrada/saída; logs da reserva no formato `event=`; excedente com INFO `event=quota.overage.authorized` + métricas `lhamalog.quota.overage` / `lhamalog.quota.overage.bytes` (tag só `productId`); testes `CorrelationIdFilterTest`, `QuotaReservationServiceTest`
- [X] T047 [P] **Collector** (acompanhamento) Converter ao formato `event=` os demais logs fora do padrão: `BatchIngestion` (entrega ao destino, release/refund), `OtlpHttpServer`/`OtlpGrpcServer` (start/stop/falha inesperada), `MongoProductTokenStore`, `AbstractStreamPublisher`, `CoreAuthenticationProvider`, `CoreUsageFactsClient`, `UsageOutboxWorker`, `UsageOutboxObservability`, `UsageAcceptanceReconciler`, `RefundOutboxWorker`, `UsageRedisStartupValidator` — feito em 16/11/2026 (inclui os WARN dos schedulers em `OtlpReceiverConfiguration`); `StartupLogger` mantido.
- [X] T048 [P] **Collector** (acompanhamento) Gerar correlation id por execução/item em `UsageOutboxWorker`, `RefundOutboxWorker`, `UsageAcceptanceReconciler` e nas retentativas assíncronas de `AbstractStreamPublisher` — feito em 16/11/2026 (MDC por item/execução com testes nos três workers).
- [ ] T049 [P] **Core** (acompanhamento) Converter ao formato `event=` os logs de `LowBalanceNotificationService` e `UsageFactsSecurityConfiguration`, e gerar correlation id nos agendadores do Core
- [X] T050 **Collector** (aprovado pelo Michel em 16/11/2026) `metadata.correlationId` dos streams reutiliza o correlation id da requisição (`CorrelationId` / MDC); contrato Finder em `contracts/stream-correlation-id.md`; `schemaVersion` 1; dedup por `idempotencyId`
- [X] T051 **Docs** Linha de inicialização da ADR_149 (`StartupLogger` nos dois repos) segue igual; apenas sai no novo formato de linha — revisar READMEs (seção Log de inicialização) quando a ADR for publicada — feito em 16/11/2026: README com seções “Padrão de logs” e “Limites por tenant (LLT-183)” e nota da linha de inicialização (ADR_149) no novo formato.

---

## Dependências e ordem

```text
Fase 1 → Fase 2 → US1 (rate) → US2 (size) → US3 (ordem) → US4 (Core+suspensão) → US5 → Polimento
```

- US1 (taxa) antes de US2 (tamanho) alinha D8 e evita ler body quando já throttled (HTTP).
- US3 valida a integração das duas.
- US4 pode começar testes Core em paralelo após Fase 1 (T004), mas integração Collector depende de outcomes da Fase 2.
- Defaults provisórios: T016/T017/T035 (obrigatórios na implementação). Confirmação final bloqueada: T009a, T041–T044.

### Paralelismo (exemplos)

```text
# Fundação
T005 || T006

# US2 testes
T010 || T011 || T012

# US4 testes Core/Collector
T025 || T026 || T027 || T028 || T029
```

## Estratégia MVP

1. Fase 1–2 + US1 (rate) + US2 (size) = proteção operacional mínima.
2. US3 fecha a ordem com spec006.
3. US4 entrega SUSPENSA/overage (requer Core).
4. US5 e polimento; confirmações [A CONFIRMAR] quando Michel responder.

## Notas

- Cobrança de excedente fora de escopo.
- Não alterar `infra/local/.env`.
- Não commit/push nesta fase Spec Kit.

## Revisão de review (PRs #6 Collector / #11 Core) - 16/11/2026

Itens de review pós-PR. Collector neste worktree; Core no PR #11 (pai marca).

- [X] R01 **Core** (item 1) `overageBytes` gravado em cada reserva e revertido em release, refund e expiração (reaper), uma única vez (guarda pela transição de estado); legados sem o campo = 0; testes unitários e de integração.
- [X] R02 **Core** (item 2) excedente calculado de forma atômica: `findAndModify` com pipeline de agregação (`$set`) calculando a parte acima da cota no momento da atualização; teste de concorrência com 50 threads (excedente ligado e desligado).
- [X] R03 **Collector** (item 3 / T050) Stream `metadata.correlationId` = cid da requisição; contrato Finder; testes HTTP/gRPC; docs README + data-model 004
- [X] R04 **Collector** (item 4) gRPC: trailer `retry-after` → `google.rpc.RetryInfo` em `grpc-status-details-bin`; HTTP Status com RetryInfo nos detalhes; testes/docs/contratos
- [X] R05 **Collector** (item 5) Docs de integração: janela Core indisponível ~25 min vs default OTel `max_elapsed_time` 300s; exemplo YAML otlp/otlphttp + file_storage
- [X] R06 **Collector** (item 6, parte Collector) Contrato `otlp-quota-rejection.md` alinhado (120/300/orçamento/RetryInfo/SUSPENDED); Javadoc Retry-After/trailer atualizado. **Parte enum Core** (ATIVA/SUSPENSA/INADIMPLENTE → ACTIVE/SUSPENDED/DELINQUENT): ver R06-Core abaixo
- [X] R06-Core **Core** (item 6, enum) `SubscriptionStatus` = `ACTIVE`/`SUSPENDED`/`DELINQUENT`; leitura de documentos antigos em português via `@ReadingConverter` e `@JsonCreator` (sem diferenciar maiúsculas); escrita em inglês; docs do Collector alinhados.

> **Decisão (sem mudança de código)**: regra dos 5 estouros — conta por requisição recusada e fica como está. Decidido pelo Michel em 16/11/2026 16:36.
