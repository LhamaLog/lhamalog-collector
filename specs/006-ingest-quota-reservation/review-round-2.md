# Revisão 2 (11/11/2026) — notas de correção

## 1. HIGH — refund em aceitação parcial

- **Decisão:** aplicar a regra do Michel. Em `SinkRejectedException`, refund somente se `acceptedCount == 0`. Com `acceptedCount > 0`, o débito confirmado (bytes como recebidos) permanece e a decisão continua `PartiallyAccepted`.
- **Verificação de sinks:** `PublishingLogsSink` / `PublishingTracesSink` chamam `store.store` antes de publicar Redis/gravar fato. `SinkRejectedException` com aceitos vem do OpenObserve (`OpenObserveClient.confirm`) **antes** de XADD e **antes** do usage outbox — registros aceitos estão no OO; não há publicação nem fato ainda. Mesmo assim a regra “publicado nunca sem débito” / “débito = reservado” manda manter o confirm.
- **SinkUnavailable / RuntimeException após XADD:** possível se `usageRecorder.accept` falhar depois de `DONE` (ou outro `RuntimeException` pós-publish). **Corrigido em 15/11 (T085):** falha depois da publicação (`DONE`) agora vem como `SinkUnavailableException.afterPublish` e não reembolsa; falha antes da publicação continua reembolsando.
- **Arquivos:** `BatchIngestion.java`, `BatchIngestionQuotaTest.java`, `spec.md`, `contracts/otlp-quota-rejection.md`, `quickstart.md`, `docs/usage-measurement.md`, `docs/quota-reservation-runbook.md`, `plan.md`.
- **Testes:** `BatchIngestionQuotaTest` (zero aceitos → refund; parcial → sem refund + `PartiallyAccepted`).

## 2. MEDIUM — reenvio ABANDONED → 503

- **Decisão:** na reabertura Mongo, emitir nova chave Redis `usage:fact:{id}:reopen:{epoch}` (`pendingReopened`). A chave original permanece `ABANDONED` e cerca o dispatcher atrasado da tentativa antiga; o reenvio mede uma vez na chave nova. **Não** reclaimar `ABANDONED` no Lua de publish (quebraria o fence).
- **Arquivos:** `UsageOutboxItem.java`, `MongoUsageOutboxRepository.java`, testes logs/traces/aceitação, nota em T063, `docs/usage-measurement.md`.
- **Testes:** `UsageOutboxFailureAcceptanceTest.abandonedResend…`, `PublishingLogsSinkTest` / `PublishingTracesSinkTest`, assert em `MongoUsageOutboxRepositoryTest`.

## 3. MEDIUM condicionais

- **(a) Core SALDO_BAIXO após débito:** corrigido. `publishPending` captura falha de publish, loga WARN, deixa PENDING para retry; `confirm` retorna OK.
- **(b) Corrida refund × reenvio:** analisada — `reservationId` é determinístico; refund atrasado pode estornar débito de retry bem-sucedido. **Não barato** → **T078**.
- **(c) Multi-doc não transacional:** analisada — CAS/idempotência por documento; transações Mongo exigem replica set. → **T079**.

## 4. Docs / LOWs / logging

- Sanitizer provisório documentado (spec/plan/docs) → evolução em **T081**.
- Pool 4 threads → **T080**.
- E2E em `src/main` referenciado pela config → **T082** (não mover).
- LOWs já corrigidos (JsonMapper T067, Lua T068, nullables T069, InMemory em test, paginação T061) não re-registrados.
- Pendentes: **T083** (DTO API no domínio), **T084** (métrica 4xx).
- Logging: tick usage e envio/resposta do client → DEBUG (**T077**).

## 5. Testes alvo (execução nesta sessão)

- Collector: `BatchIngestionQuotaTest,UsageOutboxFailureAcceptanceTest,PublishingLogsSinkTest,PublishingTracesSinkTest` → **PASS** (`.\mvnw.cmd -q test -Dtest=...`).
- Core: `LowBalanceNotificationServiceTest,QuotaReservationServiceTest` → **PASS**.
- `MongoUsageOutboxRepositoryTest` (Testcontainers) e integração Mongo do Core **não** rodados aqui (`TEST_MONGODB_URI` unset; coordenador).
- Nota T076: Core usage não tem `MeterRegistry` hoje — falha de publish `SALDO_BAIXO` fica em WARN + outbox PENDING (sem métrica nova).

## 6. LOWs verificados e não re-registrados

- JsonMapper (T067), Lua/`CLAIMED` (T068), nullables/11000 (T069), InMemory em `src/test` (T059), paginação GET (T061), validação `from < to` só no serviço (contrato) — já feitos.

## Pergunta para o Michel

Nenhuma bloqueante nesta rodada. Item 1 aplicou a regra pedida após verificar OO parcial sem publish Redis.

## Drafts de PR

- `llt186-pr-drafts/pr5-collector.md` e `pr4-core.md` (placeholders `{{COLLECTOR_TESTS}}` / `{{CORE_TESTS}}`).

---


## Checkpoint 15/11/2026 (retomada após queda do Avell)

- Execução CLI de 11/11 terminou (`AGENT_EXIT=0`); diffs revisados nos dois repositórios.
- Item 1 completado com T085 (falha pós-publicação não reembolsa). Testes alvo: `BatchIngestionQuotaTest`, `PublishingLogsSinkTest`, `PublishingTracesSinkTest`, `UsageOutboxFailureAcceptanceTest` → PASS.
- Fora do escopo (já aprovado pelo Michel em 11/11): falha **antes** da publicação no stream (OpenObserve já gravou, XADD falhou) segue com refund; o reenvio é debitado.
