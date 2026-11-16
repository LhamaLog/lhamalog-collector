# Tarefas: Limites de taxa e tamanho de lote (LLT-183)

**Entrada**: artefatos em `specs/007-plan-rate-and-size-limits/`  
**Pré-requisitos**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, contratos e `quickstart.md`.

**Testes**: obrigatórios pela Constituição III — criar testes **antes** da implementação em cada história.

**Tags**: cada tarefa indica **Collector** ou **Core**. Caminhos Core usam `../lhamalog-core-llt183/`.

**Bloqueios**: `[BLOQUEADA: A CONFIRMAR]` = Q13/Q14 (T042, T044). D4/D5 resolvidos → T041/T043 feitos na Phase C1b.

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

- [ ] T038 [P] **Collector** Executar cenários de `quickstart.md` (taxa, tamanho, ordem, SUSPENSA, overage, Core fora) e registrar evidência em `specs/007-plan-rate-and-size-limits/`
- [ ] T039 [P] **Collector** / **Core** Rodar `.\mvnw.cmd verify` nos dois worktrees e anotar resultado em `progress-notes.md`
- [ ] T040 [P] **Collector** Atualizar docs de runbook/usage se existirem em `docs/` (sem segredos; pt-BR)
- [X] T041 **Collector** D5 confirmado (16/11 sessão 2): `retry-after=5s`; violações 5/60s → bloqueio 5 min com `block-retry-after=300s`; Lua atômica; métricas `rate_limit` / `rate_limit_blocked`; testes Redis + HTTP/gRPC atualizados
- [ ] T042 [BLOQUEADA: A CONFIRMAR] **Collector** Ajustar política Redis-down quando Michel confirmar Q13
- [X] T043 **Collector** D4 confirmado (16/11 sessão 2): orçamento 5 tentativas/lote via `reservationId` determinístico; Redis `core-unavailable:{batchKey}`; 6ª → 500/INTERNAL; NO_PLAN conta; Redis erro → 503/300 sem orçamento; testes BatchIngestion + Redis tracker
- [ ] T044 [BLOQUEADA: A CONFIRMAR] **Core** Ajustar comportamento INADIMPLENTE quando Michel confirmar Q14

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
