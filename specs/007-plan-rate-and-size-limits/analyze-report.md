# Specification Analysis Report — 007-plan-rate-and-size-limits

**Data**: 2026-11-16  
**Escopo**: `spec.md`, `plan.md`, `tasks.md` (+ contratos/research/data-model para consistência)  
**Modo**: análise pós-tasks; remediações CRITICAL/HIGH aplicadas nos artefatos (não em código).

## Findings

| ID | Category | Severity | Location(s) | Summary | Recommendation / Status |
|----|----------|----------|-------------|---------|-------------------------|
| I1 | Inconsistency | HIGH | spec US order vs tasks phases | US1 era tamanho e US2 taxa, mas D8/tasks faziam taxa primeiro (MVP). | **Corrigido**: US1=taxa, US2=tamanho; tasks realinhadas. |
| I2 | Inconsistency | HIGH | tasks T016/T017/T035 | Marcadas `[BLOQUEADA]` mas pediam implementar default provisório — bloqueava o MVP. | **Corrigido**: provisório obrigatório; bloqueio só T041–T044. |
| I3 | Inconsistency | HIGH | plan/tasks/contracts Retry-After Core | Nome da propriedade do 300 s inconsistente/ausente. | **Corrigido**: `lhamalog.quota.unavailable-retry-after` em plan, FR-007, contrato, quickstart, T008. |
| A1 | Ambiguity | MEDIUM | D4 “até 5 vezes”, D5, Q13, Q14 | Itens [A CONFIRMAR]/[NEEDS CLARIFICATION] explícitos com defaults. | Manter bloqueadas T041–T044; não inventar. |
| A2 | Ambiguity | MEDIUM | AUTHORIZED_OVERAGE vs AUTHORIZED+campo | Contrato permite duas formas. | Preferir `AUTHORIZED_OVERAGE` na implementação (T031); aceitável. |
| U1 | Underspecification | MEDIUM | data-model `overageBytes` | Campo citado sem schema Mongo completo. | Detalhar na implementação Core (T031); ok para plan. |
| T1 | Terminology | LOW | reason labels EN vs docs pt-BR | Motivos de métrica em inglês no código. | Conforme Constituição IV. |
| C1 | Constitution | — | Princípios I–V | SpecKit, testes, idiomas, segredos respeitados; sem código de produção nesta fase. | OK |

**CRITICAL Issues Count**: 0 (após remediação)

## Coverage Summary

| Requirement Key | Has Task? | Task IDs | Notes |
|-----------------|-----------|----------|-------|
| FR-001 tamanho 5MB | Sim | T001, T018–T021 | |
| FR-002 taxa 20 req/s | Sim | T002, T010–T015 | |
| FR-003 escopo tenant | Sim | T013–T014 | |
| FR-004 logs+traces | Sim | T010, T011 | |
| FR-005 413 inteiro | Sim | T019–T021 | |
| FR-006 429 taxa + Retry-After | Sim | T015–T016, T041 | T041 bloqueada |
| FR-007 Core 300s / D4 | Sim | T008–T009, T043 | T043 bloqueada |
| FR-008 Redis counters | Sim | T013 | |
| FR-009 SUSPENSA / INADIMPLENTE | Sim | T004, T025–T035, T044 | T044 bloqueada |
| FR-010 observabilidade | Sim | T036–T037 | |
| FR-011 ordem taxa→tamanho→reserva | Sim | T022–T024 | |
| FR-012 não reimplementar saldo | Sim | (escopo / T024) | |
| FR-013 overage flag | Sim | T026–T027, T031–T033 | |
| FR-014 distinção recusas | Sim | T015, T021, T034, T037 | |
| FR-015 pós-auth HTTP/gRPC | Sim | T012, T014, T020 | |
| FR-016 Redis-down | Sim | T017, T042 | T042 bloqueada |
| FR-017 medição decodificada | Sim | T018, T020 | |
| SC-001..007 | Sim | quickstart T038 + testes por US | |

## Constitution Alignment Issues

Nenhum CRITICAL. Gates do plan permanecem conformes.

## Unmapped Tasks

Nenhuma tarefa órfã relevante; T038–T040 são polimento transversal.

## Metrics

- Total Requirements (FR): 17
- Total Tasks: 44 (T001–T044)
- Coverage % (FR com ≥1 task): 100%
- Ambiguity Count (abertos de negócio): 4 (D4, D5, Q13, Q14)
- Duplication Count: 0 material
- Critical Issues Count: **0**
- High Issues Count (após fix): **0**

## Next Actions

- Pode seguir para `/speckit-implement` nos itens não bloqueados (MVP: Fase 1–4 / US1+US2).
- Obter respostas do Michel para D4, D5, Q13, Q14 antes de fechar T041–T044.
- Não aplicar código nesta fase B.

## Remediation

Remediações HIGH (I1–I3) **já aplicadas** conforme missão Phase B (sem pedir confirmação). Itens MEDIUM deixados documentados de propósito.
