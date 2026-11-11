# Tarefas: Reserva de Cota para Ingestão (LLT-186)

**Entrada**: artefatos em `specs/006-ingest-quota-reservation/`  
**Pré-requisitos**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, contratos e
`quickstart.md`.

**Testes**: obrigatórios pela Constituição III e previstos antes da implementação de cada
história.

## Fase 1: Fundação compartilhada

**Objetivo**: escrever primeiro testes de contrato e de comportamento para a fundação de
reserva, antes de qualquer código de produção.

- [X] T007 [P] Criar testes de modelo, saldo compartilhado, atualização condicional e transições idempotentes em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/application/`.
- [X] T008 [P] Criar testes de documentos Mongo, índices únicos/TTL e outbox de notificação em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/infrastructure/`.
- [X] T009 [P] Criar testes de contrato autenticado para reserve/confirm/release em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/api/`.
- [X] T010 [P] Criar testes do cliente de reserva com token de serviço sem valor real em `src/test/java/br/com/lhamalog/collector/usage/core/`.
- [X] T011 [P] Criar testes de propriedades de TTL de 20 minutos e URL do Core em `src/test/java/br/com/lhamalog/collector/config/UsagePropertiesTest.java`.
- [X] T012 [P] Criar testes de métricas e logs sanitizados de reserva em `src/test/java/br/com/lhamalog/collector/usage/`.
- [X] T013 [P] Criar testes de contrato do evento `SALDO_BAIXO` em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/infrastructure/`.
- [X] T014 [P] Criar testes do transporte opcional de `reservationId` para rastreabilidade em `src/test/java/br/com/lhamalog/collector/messaging/`.
- [X] T015 Criar os componentes testados em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`, `src/main/java/br/com/lhamalog/collector/usage/core/`, `src/main/java/br/com/lhamalog/collector/config/UsageProperties.java` e `src/main/java/br/com/lhamalog/collector/usage/`.

**Checkpoint**: o Core oferece transições idempotentes e o Collector pode chamá-las sem
publicar um lote.

## Fase 2: História de Usuário 1 — Autorizar ingestão com saldo reservável (P1) 🎯 MVP

**Objetivo**: permitir que lote com saldo/ciclo válido seja reservado, confirmado e siga para
o fluxo existente com correlação à medição.

**Teste independente**: lote de logs e traces reserva em paralelo à sanitização, confirma uma
vez antes de OpenObserve/stream e preserva a medição da spec 005.

- [X] T016 [P] [US1] Criar testes unitários de saldo, ciclo UTC, `firstPaymentDate`, sem limite e idempotência em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/application/`.
- [X] T017 [P] [US1] Criar testes de persistência, índice e expiração de reserva em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/infrastructure/`.
- [X] T018 [P] [US1] Criar testes de contrato autenticado para `reserve` e `confirm` em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/api/`.
- [X] T019 [P] [US1] Criar testes do cliente de reserva com token de serviço sem valor real em `src/test/java/br/com/lhamalog/collector/usage/core/`.
- [X] T020 [P] [US1] Criar testes de integração de logs/traces que provem reserva paralela à sanitização e confirmação antes de OpenObserve/stream em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T021 [US1] Implementar no Core reserva condicional (`reservedBytes`) e confirmação que move para `consumedBytes` em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`.
- [X] T022 [US1] Implementar no Collector retenção, reserva paralela à sanitização e `confirm` síncrono antes dos sinks em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T023 [US1] Implementar encaminhamento autorizado para OpenObserve e stream/DLQ com `reservationId` somente para rastreabilidade em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/`.
- [X] T024 [US1] Correlacionar reserva confirmada com o `UsageFact`/outbox sem alterar bytes, domínio, `batchId` ou `usageFactId` em `src/main/java/br/com/lhamalog/collector/usage/`.
- [X] T025 [US1] Criar teste de integração Collector–Core com logs, traces, ciclo ativo e saldo suficiente em `src/test/java/br/com/lhamalog/collector/usage/`.

**Checkpoint**: lote com saldo suficiente é autorizado, efetivado e medido sem segunda
contagem.

## Fase 3: História de Usuário 2 — Recusar ingestão sem saldo ou ciclo pago (P1)

**Objetivo**: impedir que lote sem saldo, sem plano ou antes do primeiro ciclo pago alcance
OpenObserve, stream ou consumo.

**Teste independente**: para cada transporte OTLP, saldo insuficiente e ausência de ciclo
produzem a recusa aprovada sem efeitos posteriores.

- [X] T026 [P] [US2] Criar testes de Core para saldo insuficiente, assinatura ausente e data UTC anterior a `firstPaymentDate` em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/application/`.
- [X] T027 [P] [US2] Criar testes de contrato para os resultados sem saldo e sem ciclo em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/api/`.
- [X] T028 [P] [US2] Criar testes OTLP HTTP protobuf, HTTP JSON e gRPC para a resposta de recusa aprovada em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T029 [P] [US2] Criar teste de ausência de OpenObserve, stream, `UsageFact` e consumo em lote recusado em `src/test/java/br/com/lhamalog/collector/usage/`.
- [X] T030 [US2] Implementar mapeamento de resultado não autorizado para descarte seguro da área temporária em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T031 [US2] Implementar mapeamento da recusa aprovada para HTTP protobuf, HTTP JSON e gRPC em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T032 [US2] Implementar no Core a distinção final entre saldo insuficiente, sem limite, sem plano e sem ciclo pago em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`.
- [X] T033 [US2] Implementar a política aprovada para Core indisponível e `Retry-After` em `src/main/java/br/com/lhamalog/collector/usage/core/`.

**Checkpoint**: nenhuma rota OTLP permite enfileirar ou consumir lote recusado.

## Fase 4: História de Usuário 3 — Liberar reserva não efetivada (P2)

**Objetivo**: devolver saldo para falhas e expiração sem duplicar release ou consumo.

**Teste independente**: falha após reserva aciona release; ausência de confirmação por 20
minutos expira e estorna uma única vez.

- [X] T034 [P] [US3] Criar testes unitários de transições `RELEASED`, `EXPIRED` e repetição idempotente em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/application/`.
- [X] T035 [P] [US3] Criar testes de integração de expiração TTL/reconciliador em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/infrastructure/`.
- [X] T036 [P] [US3] Criar testes do Collector para sanitização rejeitada/falha depois da reserva e antes de `confirm` em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T037 [US3] Implementar `release` quando a sanitização rejeitar ou falhar após `reserve` e antes de `confirm` em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/`.
- [X] T038 [US3] Implementar expiração/estorno de 20 minutos, idempotência e recuperação no Core em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`.
- [X] T039 [US3] Implementar recuperação do Collector para não reenfileirar lote com reserva liberada ou expirada em `src/main/java/br/com/lhamalog/collector/usage/`.
- [X] T040 [US3] Criar teste de reinício entre reserva, publicação, confirmação e release em `src/test/java/br/com/lhamalog/collector/usage/`.

**Checkpoint**: reserva não efetivada devolve saldo uma única vez, inclusive após reinício.

## Fase 5: História de Usuário 4 — Avisar saldo baixo (P2)

**Objetivo**: emitir uma vez os eventos obrigatórios de 20% e 5% após débito confirmado.

**Teste independente**: confirmações que cruzam cada limiar produzem uma mensagem
`SALDO_BAIXO` por ciclo, sem repetição em replay.

- [X] T041 [P] [US4] Criar testes de cruzamento, chave única e replay para 20% e 5% em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/application/`.
- [X] T042 [P] [US4] Criar testes de outbox e contrato com `DONO`, `ADMINISTRADOR`, `WEB` e `EMAIL` em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/infrastructure/`.
- [X] T043 [US4] Implementar avaliação após `confirm`, chave Mongo e porta/outbox do evento em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`.
- [X] T044 [US4] Implementar adaptador Redis Streams para a porta de notificação em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/infrastructure/`.

## Fase 6: Reembolso pós-débito (P2)

- [X] T045 [P] [US5] Criar testes de contrato Core para `refund`, CAS `CONFIRMED -> REFUNDED`, replay, ilimitado, `404` e `409 RESERVATION_NOT_REFUNDABLE` em `../lhamalog-core-llt186/src/test/java/br/com/lhamalog/core/usage/`.
- [X] T046 [P] [US5] Criar testes do outbox/worker de refund para falhas OpenObserve/stream/DLQ, retry, teto, datas BSON-safe, `SUSPENDED` em `401`/`403` e `409` terminal em `src/test/java/br/com/lhamalog/collector/usage/`.
- [X] T047 [US5] Implementar rota Core `POST /internal/usage-reservations/{id}/refund`, estado `REFUNDED` e CAS de saldo em `../lhamalog-core-llt186/src/main/java/br/com/lhamalog/core/usage/`.
- [X] T048 [US5] Implementar entidade Mongo, repositório, worker, métricas `refund_enqueued`, `refund_succeeded`, `refund_retry`, `refund_suspended` e integração pós-sink em `src/main/java/br/com/lhamalog/collector/usage/` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/`.

## Fase 7: Polimento e validação transversal

- [X] T049 Executar os cenários de `specs/006-ingest-quota-reservation/quickstart.md` nos três transportes OTLP, incluindo refund e `SALDO_BAIXO`, e registrar a evidência em `specs/006-ingest-quota-reservation/` (`e2e-spec006-evidence.md`, `e2e-spec006-evidence-complementar.md`).
- [X] T050 Executar Maven Wrapper e toda a suíte aplicável nos dois repositórios, registrando resultados em `specs/006-ingest-quota-reservation/`.
- [X] T051 [P] Atualizar `docs/usage-measurement.md` e `specs/006-ingest-quota-reservation/` com comportamento efetivamente entregue, sem apagar o histórico da spec 005.
- [X] T052 [P] Atualizar métricas, logs sanitizados e runbook em `docs/` para reserva, recusa, expiração, refund, `SALDO_BAIXO` e falha do Core.

## Dependências e ordem de execução

1. T007–T014 escrevem testes; T015 estabelece contrato, persistência, cliente, configuração e observabilidade.
2. US1 (T016–T025) é o MVP funcional.
3. US2 (T026–T033) depende da fundação e pode iniciar em paralelo com US1 depois que o
   mapeamento de contrato estiver pronto.
4. US3 (T034–T040) depende da fundação e da semântica de reserva de US1.
5. US4 (T041–T044) depende da confirmação de US1 e pode seguir em paralelo a US3.
6. US5 (T045–T048) depende de US1 e valida refund antes da implementação.
7. T049–T052 dependem de todas as histórias, incluindo refund.

## Oportunidades de paralelismo

- T007–T014, T016–T020, T026–T029, T034–T036 e T041–T042 podem ser executadas em paralelo quando suas
  dependências forem satisfeitas.
- Testes de Core e Collector em cada história podem ser preparados em paralelo.
- As tarefas `[P]` referem-se a arquivos diferentes; não eliminam a dependência de decisões
  de produto e contrato.

## Estratégia de implementação

1. Escrever e fazer falhar os testes da fundação, depois implementar T015.
2. Entregar US1 e validá-la isoladamente.
3. Adicionar recusa de US2 para todos os transportes.
4. Adicionar recuperação/TTL de US3 e `SALDO_BAIXO` de US4.
5. Adicionar refund idempotente e a recuperação da outbox após US1.
6. Executar quickstart e testes locais antes de qualquer merge.
