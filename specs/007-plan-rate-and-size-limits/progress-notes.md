# Notas de progresso — spec007 (LLT-183)

Arquivo de checkpoint para retomada. Horários em America/Sao_Paulo.

## Checkpoint 16/11/2026 ~00:10–00:40 — Phase B Spec Kit (sem código de produção)

- Worktree Collector: `feature/LLT-183-plan-rate-size-limits` (base Development efa9c3c com spec005/006).
- Worktree Core (somente leitura para planejar): `feature/LLT-183-plan-rate-size-limits` (a3b2e64).
- Decisões D1–D9 do Michel (16/11/2026 via BimBot Escola de TI) registradas em `spec.md` Clarifications e `clarify-questions.md`.
- Executado: update spec/clarify → `/speckit-plan` (plan, research, data-model, contracts, quickstart) → `/speckit-tasks` → analyze + `analyze-report.md`.
- Remediações HIGH do analyze aplicadas: US1=taxa / US2=tamanho; provisórios não bloqueados; propriedade `lhamalog.quota.unavailable-retry-after`.
- **Sem** git write; **sem** código de produção; **sem** Jira/ADR; `.specify/feature.json` gitignored local.
- Analyze: 0 CRITICAL / 0 HIGH (pós-fix) / MEDIUM só abertos de negócio.
- Próximo: implementação (`/speckit-implement`) das fases não bloqueadas; confirmar D4/D5/Q13/Q14 com Michel.

## Checkpoint 16/11/2026 ~00:40–00:50 — Phase C1 Collector (T001–T003, T005–T024, T036–T037)

- Worktree: `lhamalog-collector-llt183` / `feature/LLT-183-plan-rate-size-limits`.
- **Setup**: `max-batch-size` 5MB; `RateLimitProperties` (enabled, 20 req/s, retry-after PROVISÓRIO 300s, FAIL_OPEN Q13); pacote `ratelimit/` (`TenantRateLimiter`, `RedisRateLimiter` Lua INCR+EXPIRE).
- **Fundação**: outcomes `RateLimited`, `BatchTooLarge`, `Suspended` + `QuotaGate.Outcome.SUSPENDED`; `QuotaUnavailable(retryAfter)` com default `unavailable-retry-after=300`; saldo permanece Retry-After 120.
- **US1–US3**: pipeline auth → rate → size → quota; HTTP mede body descomprimido; gRPC `getSerializedSize()`; bean desligável (`lhamalog.rate-limit.enabled`); testes sem Redis usam `allowAll` / `IngestionControls.permissive()`.
- **US5**: `IngestionObservability` — counter `lhamalog.ingestion.refused` com tag `reason` + log sanitizado.
- **Fora desta fase (Collector)**: T028/T029/T033/T034 (wiring cliente Core SUSPENSA — outra fase); T038–T044 / [A CONFIRMAR]. Core US4 (T004/T025–T027/T030–T032/T035) já coberto em checkpoint C2a.
- Variante `Suspended` + mapeamento OTLP 403/PERMISSION_DENIED existem por exaustividade do sealed switch (T007); cliente HTTP 403→SUSPENDED ainda é T033.
- Testes alvo OK: `RateLimitAndBatchSizePropertiesTest`, `IngestionDecisionOutcomesTest`, `RedisRateLimiterTest`, `BatchIngestionRateAndSizeTest`, `OtlpHttpRateAndSizeTest`, `OtlpGrpcRateAndSizeTest`, `OtlpHttpQuotaStatusMapperTest`, + regressão HTTP/gRPC/quota.
- Sem git write; sem `mvnw verify` completo; sem tocar `infra/local/.env`.

## Checkpoint 16/11/2026 ~01:00 — Phase C1b Collector (D4/D5: T041 + T043)

- **Decidido pelo Michel em 16/11/2026 via BimBot Escola de TI** (sessão 2): D4 orçamento Core 5×/lote; D5 Retry-After taxa 5 s + bloqueio 5 min.
- Rate: `RateLimitProperties` (retry-after 5, violations/window/block/block-retry-after); Lua check block→window→violations→block; `Verdict.BLOCKED`; reasons `rate_limit` / `rate_limit_blocked`.
- Core unavailable: `RedisCoreUnavailableAttemptTracker` + `QuotaUnavailableExhausted` → HTTP 500 / gRPC INTERNAL; batch key = reservationId determinístico `CanonicalQuotaGate`; NO_PLAN conta; clear no confirm ok.
- Spec/clarify/plan/data-model/contracts/quickstart/tasks atualizados (pt-BR); T042/T044 permanecem bloqueadas.
- Testes alvo OK (exit 0): `RateLimitAndBatchSizePropertiesTest`, `QuotaPropertiesTest`, `IngestionDecisionOutcomesTest`, `RedisRateLimiterTest`, `RedisCoreUnavailableAttemptTrackerTest`, `BatchIngestionCoreUnavailableBudgetTest`, `BatchIngestionRateAndSizeTest`, `OtlpHttpRateAndSizeTest`, `OtlpGrpcRateAndSizeTest`, `OtlpHttpQuotaStatusMapperTest`, `RefundOutboxWorkerTest`.
- Sem git write; sem `mvnw verify` completo; sem tocar Core worktree / `.env`.

## Checkpoint 16/11/2026 ~00:45 — Phase C2a Core US4 (SUSPENSA / overage)

- Worktree Core: `feature/LLT-183-plan-rate-size-limits` — tarefas **T004, T025, T026, T027, T030, T031, T032, T035** concluídas.
- `SubscriptionStatus` (ATIVA/SUSPENSA/INADIMPLENTE); `PlanSubscription` com `status` + `overageEnabled` (legado → ATIVA/false via `@PersistenceCreator`).
- `QuotaReservationService`: SUSPENSA → `SUSPENDED` (sem reserva; status antes de NO_PLAN); `overageEnabled` + saldo insuficiente → `AUTHORIZED` + `overageBytes` no body e acumulador no saldo do ciclo; INADIMPLENTE tratado como ATIVA com WARN até Q14/T044.
- `UsageReservationsController`: `SUSPENDED` → HTTP 403; overage → 201/200 `outcome=AUTHORIZED` + `overageBytes`.
- **Collector compat**: escolhido `AUTHORIZED` + `overageBytes` (contrato permite AUTHORIZED_OVERAGE **ou** AUTHORIZED+campo). `HttpQuotaReservationClient` faz `valueOf` só de AUTHORIZED/AUTHORIZED_UNLIMITED/… — AUTHORIZED já é aceito como autorizado sem T033. 403 `SUSPENDED` ainda exige **T033** (hoje cai no `default` → UNAVAILABLE).
- `QuotaBalanceDocument.overageBytes` com default legado 0 via `@PersistenceCreator`.
- Testes locais: `PlanSubscriptionTest` (3), `QuotaReservationServiceTest` (12), `UsageReservationsControllerContractTest` (3) — BUILD SUCCESS. Integração Mongo (`TEST_MONGODB_URI`) não rodada (coordenador).
- Sem git write; Collector código intocado; T044 permanece bloqueada.

## Checkpoint 16/11/2026 ~01:10 — Phase C2b Collector US4 (SUSPENSA / overage compat)

- Worktree Collector: `feature/LLT-183-plan-rate-size-limits` — tarefas **T028, T029, T033, T034**.
- `QuotaReservationClient.ReservationOutcome.SUSPENDED`; `HttpQuotaReservationClient`: 403 + `outcome=SUSPENDED` → `SUSPENDED`; 403 sem esse body → `UnavailableException` (não confunde com suspensão); parse de 200/201 tolera `overageBytes` e campos desconhecidos (tree + `valueOf` do outcome).
- `CanonicalQuotaGate` mapeia `SUSPENDED` → `QuotaGate.Reservation.Outcome.SUSPENDED`; `BatchIngestion` → `IngestionDecision.Suspended` (sem confirm/release/refund e **sem** consumir orçamento Core-unavailable).
- OTLP já tinha mapeamento (C1): HTTP 403 / gRPC `PERMISSION_DENIED` + `reason=subscription_suspended`.
- **Overage métrica**: não adicionada — cliente só expõe enum `AUTHORIZED` (Core não envia `AUTHORIZED_OVERAGE`); contar `overageBytes` exigiria mudar o contrato do client; adiado.
- Contrato `usage-reservations-extension.md` alinhado ao shape Core (`AUTHORIZED` + `overageBytes` opcional).
- Testes alvo OK (exit 0): `HttpQuotaReservationClientTest`, `CanonicalQuotaGateTest`, `BatchIngestionQuotaTest`, `OtlpHttpSuspendedTest`, `OtlpGrpcSuspendedTest`, `OtlpHttpQuotaStatusMapperTest`.
- Sem git write; Core worktree não editado; sem `mvnw verify` completo; sem tocar `infra/local/.env`.


## Checkpoint 16/11/2026 01:25 — verify

- Collector (`mvnw.cmd -B verify`, worktree `lhamalog-collector-llt183`): **279 testes, 0 falhas, 0 erros, 0 ignorados — exit 0**.
- Core (`mvnw.cmd -B verify` com Mongo 8.0.34 descartável na porta 27019, removido ao final): **87 testes, 1 falha** em `CompanyRegistrationHttpTests.adminDeletesRegistrationAndMemberCannot` (esperado 409, veio 200). A mesma falha ocorre no `origin/Development` puro (`a3b2e64`, sem as mudanças da LLT-183) — falha pré-existente do módulo de empresa (LLT-175), não relacionada a esta feature. Testes da LLT-183 no Core passam.
- T039 fica aberta até o verify do Core ficar verde. T038 (cenários do quickstart ponta a ponta) e T040 (docs/runbook) seguem abertas.
