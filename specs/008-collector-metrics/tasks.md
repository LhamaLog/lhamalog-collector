# Tasks: Expor métricas do Collector (LLT-187)

**Input**: Design documents from `specs/008-collector-metrics/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo
- **[Story]**: US1…US4

## Phase 1: Setup

- [X] T001 Criar/atualizar estrutura `specs/008-collector-metrics/` (já feita) e `logs/` no `.gitignore`
- [X] T002 Adicionar dependência `micrometer-registry-prometheus` no `pom.xml`
- [X] T003 [P] Props: management port/address, exposure `health,info,prometheus`, `lhamalog.metrics.*`, `logging.file.name` no perfil local; placeholders em `infra/local/.env.example`

## Phase 2: Foundational

- [X] T004 SecurityFilterChain dupla (management permitAll health/info/prometheus + catch-all autenticado) em `src/main/java/.../config/`
- [X] T005 [P] MeterFilter removendo tags proibidas + common tag `application`
- [X] T006 [P] Testes de segurança/scrape do endpoint prometheus e negação de env

**Checkpoint**: scrape 200 com JVM meters; env não exposto

## Phase 3: US1 + US2 — Cinco métricas e instrumentação (P1)

- [X] T007 [US2] Estender observabilidade: accepted records/bytes + latency histogram (buckets SLO)
- [X] T008 [US2] Instrumentar receivers HTTP/gRPC (protocol, signal, outcome)
- [X] T009 [US2] Incrementar accepted após sink/publish com bytes canônicos
- [X] T010 [US2] Queue/DLQ gauges + refresher agendado + WARN rate-limited
- [X] T011 [P] [US2] Unit tests SimpleMeterRegistry por meter + filtro de tags
- [X] T012 [US1] Integration/scrape test mostrando `lhamalog_` no `/actuator/prometheus`

**Checkpoint**: cinco métricas no scrape

## Phase 4: US3 — Stack observabilidade (P2)

- [X] T013 [US3] `infra/local/compose.observability.yaml` (prometheus, loki, alloy, grafana) tags pinadas
- [X] T014 [P] [US3] Configs prometheus.yml, loki, alloy (tail logs)
- [X] T015 [P] [US3] Grafana provisioning datasources + dashboard JSON
- [X] T016 [US3] Makefile targets `metrics-up|down|ps|logs|metrics-config`; corrigir comentário Elasticsearch→OpenObserve

**Checkpoint**: `docker compose ... config -q` ok

## Phase 5: US4 — Docs (P3)

- [X] T017 [US4] `specs/008-collector-metrics/metricas.md` (todas métricas) + link no runbook
- [X] T018 [P] [US4] `docs/observability-runbook.md`
- [X] T019 [P] [US4] Seção README "Métricas e logs (LLT-187)"
- [X] T020 [US4] Atualizar quickstart/progress-notes com validação final

## Phase 6: Verify

- [X] T021 Rodar `mvnw.cmd -B verify` (verde)
- [X] T022 Validar `docker compose -p lhamalog-observability -f infra/local/compose.observability.yaml config -q`

## Dependencies

- Phase 1 → 2 → 3 → 4/5 (docs podem paralelizar com 4) → 6
- US3 depende de US1/US2 para painéis úteis, mas arquivos compose podem ser escritos em paralelo após T003

## Notes

- Nunca git write; nunca ler `infra/local/.env`
- Sem métricas além das 5 Q4
- Sem alterações no Core / TelemetryIngestedPublisher
- [X] T023 Verificação local com containers (16/11/2026 14:20 BRT): Prometheus raspando o Collector (alvo up), Grafana com datasources/dashboard provisionados e log com `cid` no Loki — evidência em `live-check-evidence.md`. Correções achadas: gauges de fila (sem `@ConditionalOnBean`), Alloy preservando a linha completa, cadeia de segurança padrão mantendo CSRF/form login.
- [X] T024 Railway IaC do módulo de observabilidade (16/11/2026, sem deploy): `.railway/railway.ts` (partial `observability`) + `infra/railway/{prometheus,loki,alloy,grafana}`. Prometheus/Loki/Alloy só na rede privada, Grafana sem anônimo e senha só em variável da Railway. Validado com `tsc`, `docker build` e containers locais; seção "Railway" no runbook.
- [ ] T025 (follow-up) Exportar logs do Collector via OTLP para o Alloy na Railway e configurar `MANAGEMENT_ADDRESS=::` no serviço do Collector.
