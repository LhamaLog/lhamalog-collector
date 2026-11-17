# Tasks: 009-raw-log-retention (LLT-185)

**Feature**: Retenção de logs brutos / streams por tenant  
**Branch**: `feature/LLT-185-raw-log-retention`  
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Phase 1 — Setup

- [x] T001 Criar `specs/009-raw-log-retention/progress-notes.md` com checkpoint inicial
- [x] T002 [P] Confirmar propriedades `OPENOBSERVE_LOGS_STREAM`/`TRACES_STREAM` como prefixos em `src/main/resources/application.properties` e comentários em `OtlpReceiverProperties.java`

## Phase 2 — Foundational

- [x] T003 Implementar `TenantStreamNames` em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/TenantStreamNames.java` conforme `contracts/tenant-stream-naming.md`
- [x] T004 [P] Testes unitários naming (colisão, truncamento, edge) em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/sink/TenantStreamNamesTest.java`
- [x] T005 Estender `QuotaReservationClient` com `ReservationResult(outcome, Integer retentionDays)` e parsing tolerante em `HttpQuotaReservationClient.java`
- [x] T006 [P] Atualizar `QuotaGate.Reservation` + `CanonicalQuotaGate` + `RetentionCorrelation` para propagar `retentionDays`
- [x] T007 [P] Atualizar testes `HttpQuotaReservationClientTest`, `CanonicalQuotaGateTest` e mocks de `ReservationOutcome`

## Phase 3 — User Story 1 (stream por tenant) [US1]

- [x] T008 [US1] Alterar `OpenObserveLogsSink` / `OpenObserveTracesSink` para resolver stream via `TenantStreamNames` a partir do prefixo + tenantId
- [x] T009 [US1] Ajustar `OtlpReceiverConfiguration` beans dos sinks (prefixo em vez de nome fixo)
- [x] T010 [P] [US1] Atualizar testes de sink/aceitação que assertam stream `"logs"`/`"traces"` (`OpenObserveSinkTest`, `ExportLogsAcceptanceTest`, `TelemetryIngestedPublisherTest`, etc.)
- [x] T011 [US1] Garantir `StoredBatch.stream` e Redis `OpenObserveRef.stream` = stream do tenant (sem schemaVersion bump)

## Phase 4 — User Story 2 (retenção) [US2]

- [x] T012 [US2] Adicionar `putStreamRetention(stream, type, days)` em `OpenObserveClient.java`
- [x] T013 [US2] Implementar `StreamRetentionService` (Caffeine TTL 1h, backoff 60s, async + correlation id, métrica Micrometer) em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/StreamRetentionService.java`
- [x] T014 [US2] Disparar ensure-retention após ingest OK nos sinks (ou publishing wrapper); `BatchIngestion` seta `RetentionCorrelation`
- [x] T015 [P] [US2] Testes unitários cache/backoff/unknown em `StreamRetentionServiceTest.java`
- [x] T016 [US2] Integração Testcontainers OO: dois tenants retention 7 e 30 + falha settings sem recusar lote em `OpenObserveRetentionIntegrationTest.java`
- [x] T017 [US2] WARN `event=openobserve.stream_retention.apply_failed` e counter `lhamalog.openobserve.retention.apply`

## Phase 5 — User Story 3 (docs + Core contract) [US3]

- [x] T018 [P] [US3] Escrever `docs/raw-log-retention-runbook.md` (pt-BR): legado, UNION, métricas, falhas
- [x] T019 [P] [US3] Atualizar seção no `README.md` e notas `infra/local` se relevante
- [x] T020 [US3] Fechar `quickstart.md` com passos reais de verificação

### Core (outro repo — parent verifica; NÃO editar Core aqui)

- [X] T021 **Core** Expor `retentionDays` nas respostas AUTHORIZED / AUTHORIZED_UNLIMITED (e overage) de `POST /internal/usage-reservations`
- [X] T022 **Core** Calcular valor = máximo `retencaoDias` (PlanTier) entre assinaturas do tenant com plano
- [X] T023 **Core** Testes de serialização + compatibilidade (omitir em outcomes não autorizados)
- [X] T024 **Core** Documentar contrato espelhando `contracts/usage-reservations-retention.md`

## Phase 6 — Polish

- [x] T025 Rodar `.\mvnw.cmd -B verify` e registrar contagens em progress-notes
- [x] T026 Marcar todas as tasks Collector concluídas; listar commits propostos `LLT-185: …`
- [x] T027 Escrever `analyze-report.md` se ainda não existir pós-analyze

## Dependencies

- T003–T007 antes de US1/US2
- US1 (T008–T011) antes ou em paralelo leve com US2 (T012+)
- T016 após T012–T014
- Core T021–T024 paralelos ao Collector (parent)

## Parallel examples

- T004 ∥ T005 após T003
- T018 ∥ T019
- T021–T024 ∥ todo Collector

## MVP

T003–T011 + T012–T017 (US1+US2). Docs e Core fecham a feature completa.
- [X] T900 Verificação local real (16/11/2026 15:19 BRT): dois tenants FREE e PRO → streams com `data_retention` 7 e 30 via API; evidência em `live-check-evidence.md`.
- [X] T025 **Core** Assinatura sem `planTier` (ATIVA/INADIMPLENTE) conta como FREE (7) no `TenantRetentionResolver`; `retentionDays` só fica null sem assinatura considerável; testes (decisão do Michel 16/11/2026 16:36).
- [X] T026 **Docs** Spec 009 (sessão de clarify 16:36 com as 5 decisões), contrato da reserva, research, analyze, runbook e README alinhados; Collector sem mudança de código (ramo sem `retentionDays` mantido por compatibilidade).
- [X] T027 **Review BimBot (16/11 17:36)** Core: INADIMPLENTE com carência vencida fora do cálculo (regra única `DelinquencyGrace`), testes de borda; WARN para `planTier` desconhecido. Collector: fila limitada + shutdown no `StreamRetentionService`, `retentionDays` não inteiro rejeitado, CHANGELOG, exemplo de override e limitação do downgrade com stream parado no runbook.
