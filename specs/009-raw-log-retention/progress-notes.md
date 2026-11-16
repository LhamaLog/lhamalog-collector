# Progress notes — 009-raw-log-retention

## 2026-11-16

- Spec Kit: specify + clarify (decisões Michel) + plan + tasks + analyze concluídos.
- Pasta `specs/009-raw-log-retention/` criada à mão (sem `create-new-feature.ps1`).
- `.specify/feature.json` → `specs/009-raw-log-retention`.
- Implementação Collector concluída (naming, ReservationResult, RetentionCorrelation, StreamRetentionService async, sinks por tenant, docs).
- Tasks Core T021–T024 ficam abertas para o parent no repo Core.
- `.\mvnw.cmd -B verify`: **Tests run: 307, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS.

## Checkpoint 16/11/2026 15:20 (BRT) — Core e verificação local

- Core (`lhamalog-core-llt185`): `PlanTier` (ADR_131), `planTier` opcional em `PlanSubscription`, `TenantRetentionResolver` (máximo entre assinaturas do tenant com plano, exclui SUSPENSA) e `retentionDays` na resposta da reserva. Verify: 115 testes, só a falha conhecida da LLT-175.
- Collector: verify 307 testes, 0 falhas.
- Verificação local: streams `logs_tenant_free_acme_1d000cfd` (7) e `logs_tenant_pro` (30); legado sem plano fica com o padrão global (comportamento anterior à decisão de 16:36; ver checkpoint abaixo). Nada commitado.

## Checkpoint 16/11/2026 16:45 (BRT) — decisões do Michel (16:36)

- As 5 perguntas foram respondidas; só a 2 mudou o comportamento: assinatura sem plano agora conta como FREE (7 dias) no Core.
- Collector sem mudança de código; docs e contrato atualizados.
- Verificação local refeita às 16:44 (BRT): FREE 7, PRO 30, sem plano 7. Verify: Core 120 testes (1 falha conhecida LLT-175, 5 ignorados); Collector 307 testes, 0 falhas.

## Checkpoint 16/11/2026 17:50 (BRT) — review do BimBot

- Bloqueante corrigido: INADIMPLENTE com carência vencida não conta mais na retenção (spec, decisão 4, atualizada).
- Não bloqueantes: ver T027. Nada commitado.

