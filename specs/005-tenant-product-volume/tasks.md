---
description: "Tarefas de implementação para LLT-186"
---

# Tasks: Volume por Tenant e Produto (LLT-186)

**Entrada**: Artefatos em `specs/005-tenant-product-volume/`
**Responsável**: michelbocchijr@gmail.com
**Pré-requisitos**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/usage-facts-api.md` e `quickstart.md`
**Estratégia de testes**: TDD obrigatório — escrever cada teste, executá-lo para confirmar falha e só então implementar a tarefa correspondente.

## Convenções de escopo

- **Collector**: alterações neste repositório, `lhamalog-collector`.
- **Core**: alterações no repositório irmão `lhamalog-core`, referenciado nos caminhos como `../lhamalog-core/`.
- LLT-186 cria no Core o modelo de ciclo do plano; saldo, reserva e cota continuam fora de escopo e pertencem à LLT-183. A franquia é mensal, plano semanal usa ciclo semanal, plano semestral é cobrado mês a mês e o ciclo começa na data obrigatória do primeiro pagamento da nova entidade de plano/assinatura.
- A autenticação usa o token dedicado `LHAMALOG_COLLECTOR_CORE_TOKEN` no Collector e `LHAMALOG_CORE_USAGE_TOKEN` no Core, exclusivamente por configuração/segredo de ambiente, no cabeçalho `X-LhamaLog-Service-Token`; a API não aceita Clerk nem `Authorization`. Testes injetam valor de teste e não incluem tokens, senhas ou chaves versionados.
- A garantia Redis é condicional: não alterar `appendfsync` compartilhado nem exigir Cluster. Com AOF `everysec`, o RPO pode ser de até ~1 s após `200`; manter Lua sem rollback e a matriz `CLAIMED`/`DONE`/`ABANDONED`/`AMBIGUOUS`. `appendfsync always` é recomendação operacional pendente, não tarefa de implementação. `firstPaymentDate` é dependência externa; UTC é o default técnico para `acceptedAt`.

## Phase 1: Setup (infraestrutura compartilhada)

**Purpose**: Preparar configuração e infraestrutura de teste sem alterar o fluxo de ingestão.

- [X] T001 [P] **Collector** Criar a seção `lhamalog.usage` desabilitada por padrão, com URL do Core, nome da variável `LHAMALOG_COLLECTOR_CORE_TOKEN` (sem valor), limites de backoff, lease, intervalo do trabalhador, `grace` de liveness (maior que a publicação máxima com retries) e expiração de sete dias para a chave de deduplicação, em `src/main/java/br/com/lhamalog/collector/config/UsageProperties.java`
- [X] T002 [P] **Collector** Registrar propriedades de uso e beans condicionais na configuração Spring em `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverConfiguration.java`
- [X] T003 [P] **Collector** Estender o ambiente de integração com MongoDB para a outbox de uso em `src/test/java/br/com/lhamalog/collector/TestcontainersConfiguration.java`
- [X] T004 [P] **Collector** Documentar as variáveis não secretas, o nome `LHAMALOG_COLLECTOR_CORE_TOKEN` sem valor e o modo local com stub autenticado do Core em `infra/local/.env.example`

---

## Phase 2: Foundational (pré-requisitos bloqueantes)

**Purpose**: Definir o núcleo imutável do fato e a outbox durável, necessário para todas as histórias.

**⚠️ CRITICAL**: Nenhuma história deve conectar o fluxo OTLP ou enviar ao Core antes de esta fase estar concluída.

### Testes TDD da fundação

- [X] T005 [P] **Collector** Escrever testes de `UsageFact` para exigir `domain` exatamente `LOGS` ou `TRACES`, IDs sem duplicatas, contagem válida, bytes ≥ 0 e `schemaVersion` `1` em `src/test/java/br/com/lhamalog/collector/usage/domain/UsageFactTest.java`
- [X] T006 [P] **Collector** Escrever testes para `usageFactId = SHA-256(tenantId, productId, domain, batchId)`, `batchId` SHA-256 da lista ordenada de `recordIds` e reenvio idempotente em `src/test/java/br/com/lhamalog/collector/usage/domain/UsageFactIdTest.java`
- [X] T007 [P] **Collector** Escrever teste Testcontainers Mongo para `_id` único, *write concern* `majority`, conteúdo `PENDING` imutável e erro sem sobrescrita quando mesmo id tiver conteúdo divergente em `src/test/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepositoryTest.java`
- [X] T008 [P] **Collector** Escrever testes CAS das transições `PENDING → ACCEPTED|ABANDONED|AMBIGUOUS`, `ACCEPTED → SYNCED` e lease/retry HTTP como atributos de sincronização em `src/test/java/br/com/lhamalog/collector/usage/outbox/UsageOutboxItemTest.java`

### Implementação da fundação

- [X] T009 **Collector** Implementar `UsageDomain`, `UsageFact` e o gerador de `usageFactId` determinístico em `src/main/java/br/com/lhamalog/collector/usage/domain/UsageFact.java`
- [X] T010 **Collector** Implementar `UsageOutboxItem`, estados, lease e transições em `src/main/java/br/com/lhamalog/collector/usage/outbox/UsageOutboxItem.java`
- [X] T011 **Collector** Implementar repositório Mongo com índice único em `_id`, inserção idempotente e aquisição de lease em `src/main/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepository.java`
- [X] T012 **Collector** Criar a porta de persistência da outbox em `src/main/java/br/com/lhamalog/collector/usage/outbox/UsageOutboxRepository.java`

**Checkpoint**: O Collector consegue representar e persistir uma única vez um fato de uso, mas ainda não o produz nem o transmite.

---

## Phase 3: User Story 1 - Reconhecer o volume aceito por domínio (Priority: P1) 🎯 MVP

**Goal**: Criar um fato por lote e domínio que separe tenant e produto, conte somente registros válidos e meça o protobuf OTLP canônico reconstruído.

**Independent Test**: Enviar logs e traces válidos de tenants/produtos distintos, inclusive lote parcial, e verificar fatos separados com contagem e bytes do subconjunto aceito.

### Testes TDD da User Story 1

- [X] T013 [P] [US1] **Collector** Escrever testes de logs para preservar ordem/hierarquia de `ResourceLogs` e `ScopeLogs`, remover grupos vazios e serializar deterministicamente em `src/test/java/br/com/lhamalog/collector/usage/domain/CanonicalLogsMeasurementTest.java`
- [X] T014 [P] [US1] **Collector** Escrever testes equivalentes de traces com grupos repetidos e ordem de `ResourceSpans`/`ScopeSpans` em `src/test/java/br/com/lhamalog/collector/usage/domain/CanonicalTracesMeasurementTest.java`
- [X] T015 [P] [US1] **Collector** Escrever testes de aceitação HTTP protobuf, HTTP JSON e gRPC para logs/traces, inclusive corpo comprimido, que convirjam à mesma mensagem e bytes; compressão não entra na medida, em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/UsageMeasurementTransportAcceptanceTest.java`
- [X] T016 [P] [US1] **Collector** Escrever teste de lote parcialmente válido que exija um fato com somente `recordId`s válidos, tenant/produto do token e domínio correto em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/PartialBatchUsageFactAcceptanceTest.java`

### Implementação da User Story 1

- [X] T017 [US1] **Collector** Implementar reconstrução protobuf canônica por subconjunto aceito, sem `tenantId`, `productId`, `lhamalog_batch_id`, `lhamalog_record_id` ou metadados internos no payload medido, em `src/main/java/br/com/lhamalog/collector/usage/domain/CanonicalOtlpMeasurement.java`
- [X] T018 [US1] **Collector** Implementar fábrica de fatos que derive `batchId` e `acceptedRecordIds` de `StoredBatch`, defina `acceptedAt` UTC e gere um fato por domínio em `src/main/java/br/com/lhamalog/collector/usage/domain/UsageFactFactory.java`
- [X] T019 [US1] **Collector** Adaptar o caminho de logs para fornecer o request original e o subconjunto aceito à fábrica de medição em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsService.java`
- [X] T020 [US1] **Collector** Adaptar o caminho de traces para fornecer o request original e o subconjunto aceito à fábrica de medição em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportTracesService.java`

**Checkpoint**: A medição de um lote aceito é determinística, independente do transporte e não mistura logs com traces, tenants ou produtos.

---

## Phase 4: User Story 2 - Reconhecer somente após o encaminhamento confirmado (Priority: P1)

**Goal**: Persistir o fato de uso somente depois de OpenObserve e stream/DLQ confirmados, fazendo a persistência da outbox parte da confirmação final ao exportador.

**Independent Test**: Exercitar confirmações por stream e DLQ, além de falhas de OpenObserve, publicação e Mongo, e verificar quando há fato e quando o exportador recebe indisponibilidade.

### Testes TDD da User Story 2

- [X] T021 [P] [US2] **Collector** Escrever testes de logs para `PENDING` com `majority` e Lua (`DONE`/`ABANDONED` sem `XADD`; ausente→`CLAIMED`→`XADD`→`DONE`) em main e DLQ em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/PublishingLogsSinkTest.java`
- [X] T022 [P] [US2] **Collector** Escrever os mesmos testes de protocolo para traces em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/PublishingTracesSinkTest.java`
- [X] T023 [P] [US2] **Collector** Escrever a matriz TDD compacta de crashes: erro `XADD`→`CLAIMED`; erro após `XADD`→`AMBIGUOUS`; crash `DONE`→Mongo `ACCEPTED` reconcilia; reconciliador vs dispatcher lento (`ABANDONED` bloqueia `XADD`); reenvio concorrente (um `XADD`); main falha/DLQ sucesso (uma evidência); ambas falham (`503`); HTTP 200 DLQ sem `DONE` não cria `ACCEPTED`; conteúdo divergente no mesmo id; JSON/proto HTTP/gRPC e compressão com mesmo `batchId`/bytes, em `src/test/java/br/com/lhamalog/collector/ingestion/otlp/UsageOutboxFailureAcceptanceTest.java`
- [X] T024 [P] [US2] **Collector** Escrever testes Testcontainers Redis/Mongo de reconciliador startup/periódico: CAS ausente→`ABANDONED`, `DONE`→`ACCEPTED`, `CLAIMED`→`AMBIGUOUS`; grace é apenas liveness e a chave de deduplicação expira após sete dias, em `src/test/java/br/com/lhamalog/collector/usage/outbox/UsageReconcilerIntegrationTest.java`

### Implementação da User Story 2

- [X] T025 [US2] **Collector** Criar porta que persista `PENDING` com `majority`, faça CAS de estado e trate conteúdo divergente ou falha antes de `DONE` como indisponibilidade em `src/main/java/br/com/lhamalog/collector/usage/outbox/UsageFactRecorder.java`
- [X] T026 [US2] **Collector** Integrar logs: OpenObserve → candidato Mongo → Lua com `usage:fact:{usageFactId}` (main ou DLQ) → CAS `ACCEPTED`, respondendo `200` após `DONE` mesmo se o CAS Mongo falhar, em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/PublishingLogsSink.java`
- [X] T027 [US2] **Collector** Implementar o mesmo protocolo Lua/CAS para traces em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/PublishingTracesSink.java`
- [X] T028 [US2] **Collector** Implementar Lua e reconciliador CAS (startup/periódico), alertando `CLAIMED`/`AMBIGUOUS` e aplicando expiração de sete dias a `usage:fact:*`, em `src/main/java/br/com/lhamalog/collector/usage/outbox/UsageAcceptanceReconciler.java`

**Checkpoint**: O sucesso OTLP só ocorre quando OpenObserve, stream/DLQ e outbox estão confirmados; Core indisponível ainda não é consultado nesse caminho.

---

## Phase 5: User Story 3 - Agregar o uso no ciclo do plano sem cobrança duplicada (Priority: P2)

**Goal**: Entregar fatos duráveis ao Core com retry e idempotência, e fazer o Core agregar bytes e registros conforme o contrato.

**Independent Test**: Reenviar o mesmo fato dentro de sete dias, enviar um novo fato com registro já medido e simular offline/timeout/reinício; validar uma única agregação para o reenvio idêntico, agregação integral do fato novo e nova contagem possível após sete dias.

### Testes TDD da User Story 3 — Collector

- [X] T029 [P] [US3] **Collector** Escrever teste de contrato para `POST /internal/usage-facts` com `X-LhamaLog-Service-Token`, corpo com `usageFactId` como chave idempotente e ausência de `Authorization`/`Idempotency-Key`, em `src/test/java/br/com/lhamalog/collector/usage/core/CoreUsageFactsClientContractTest.java`
- [X] T030 [P] [US3] **Collector** Escrever testes do trabalhador para `ACCEPTED` + 2xx→`SYNCED`, `429/5xx`, timeout ou rede mantendo `ACCEPTED` com backoff/jitter e 4xx suspendendo retry automático sem desfazer aceitação em `src/test/java/br/com/lhamalog/collector/usage/core/UsageOutboxWorkerTest.java`
- [X] T031 [P] [US3] **Collector** Escrever teste Testcontainers de reinício e lease expirado que exija recuperar item pendente e repetir a mesma chave de idempotência em `src/test/java/br/com/lhamalog/collector/usage/core/UsageOutboxRecoveryIntegrationTest.java`

### Testes TDD da User Story 3 — Core (`lhamalog-core`)

- [X] T032 [P] [US3] **Core** Escrever teste de contrato para `POST /internal/usage-facts`, `GET /internal/usage-facts?tenantId&productId&from&to` e `GET /internal/usage-facts/current-cycle?tenantId&productId`, validando campos, `domain` `LOGS`/`TRACES`, lista sem duplicatas, contagem positiva, instante UTC e janela `[from,to)`, em `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/api/UsageFactsControllerContractTest.java`
- [X] T033 [P] [US3] **Core** Escrever teste de integração que exija idempotência atômica por `usageFactId`: corpo igual retorna `200/201` sem nova agregação e corpo diferente retorna `409` em `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/application/UsageFactIdempotencyIntegrationTest.java`
- [X] T034 [P] [US3] **Core** Escrever teste de deduplicação por `batchId`/`usageFactId` por sete dias, agregação integral de lote novo e diferente mesmo com registro já medido, GB decimal e ciclo definido em [RF-006 e RF-014](spec.md#requisitos-funcionais) com `firstPaymentDate` obrigatório (semanal, semestral mês a mês e UTC) em `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/application/UsageAggregationServiceTest.java`

### Implementação da User Story 3 — Collector

- [X] T035 [US3] **Collector** Implementar cliente para `POST /internal/usage-facts`, com `X-LhamaLog-Service-Token` lido somente de `LHAMALOG_COLLECTOR_CORE_TOKEN`, `usageFactId` no corpo como idempotência e sem payload OTLP/metadados internos, em `src/main/java/br/com/lhamalog/collector/usage/core/CoreUsageFactsClient.java`
- [X] T036 [US3] **Collector** Implementar trabalhador HTTP para somente `ACCEPTED`, com lease, recuperação após reinício, backoff limitado/jitter e logs sanitizados, marcando `SYNCED` apenas após 2xx, em `src/main/java/br/com/lhamalog/collector/usage/core/UsageOutboxWorker.java`
- [X] T037 [US3] **Collector** Implementar provedor que leia exclusivamente `LHAMALOG_COLLECTOR_CORE_TOKEN` por configuração/segredo de ambiente e injete `X-LhamaLog-Service-Token`, sem registrar o valor; testes devem injetar valor de teste, em `src/main/java/br/com/lhamalog/collector/usage/core/CoreAuthenticationProvider.java`

### Implementação da User Story 3 — Core (`lhamalog-core`)

- [X] T038 [US3] **Core** Implementar DTO, validação e `POST /internal/usage-facts` com respostas `201/200/409/400/422` conforme `contracts/usage-facts-api.md` em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/api/UsageFactsController.java`
- [X] T039 [US3] **Core** Implementar persistência atômica por `usageFactId`, expiração de deduplicação por lote após sete dias e consulta por janela `[from,to)` em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/UsageFactRepository.java`
- [X] T040 [US3] **Core** Implementar a nova entidade de plano/assinatura com `firstPaymentDate` obrigatório, o modelo de agregação por ciclo definido em [RF-006 e RF-014](spec.md#requisitos-funcionais) e `GET /internal/usage-facts/current-cycle?tenantId&productId`: franquia mensal, ciclo semanal para plano semanal, cobrança mês a mês para plano semestral e UTC como default técnico para converter `acceptedAt`; não criar cotas ou reservas, em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/application/UsageAggregationService.java`
- [X] T041 [US3] **Core** Aplicar à API interna de medição o token de serviço dedicado de `LHAMALOG_CORE_USAGE_TOKEN`, aceitar somente `X-LhamaLog-Service-Token`, recusar Clerk/`Authorization` e retornar `401/403` sem registrar credenciais, em `../lhamalog-core/src/main/java/br/com/lhamalog/core/config/UsageFactsSecurityConfiguration.java`

**Checkpoint**: O Collector confirma a ingestão sem depender da disponibilidade do Core e ambos os lados toleram entrega ao menos uma vez, sem duplicar o fato.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Validar a jornada completa e manter a documentação operacional consistente.

- [X] T042 [P] **Collector** Criar métricas e logs sanitizados para estados, tentativas, idade, `PENDING` além do grace e alertas `AMBIGUOUS`, sem payload OTLP ou credenciais; observar também a expiração de sete dias da chave de deduplicação, em `src/main/java/br/com/lhamalog/collector/usage/core/UsageOutboxObservability.java`
- [X] T043 [P] **Collector** Atualizar o roteiro de ambiente local, stub autenticado por `X-LhamaLog-Service-Token` com valor de teste injetado e validação dos cenários de retry em `specs/005-tenant-product-volume/quickstart.md`
- [X] T044 **Collector** Executar `.\mvnw.cmd clean verify` e registrar o resultado da suíte no checklist de validação em `specs/005-tenant-product-volume/quickstart.md`
- [X] T045 **Core** Executar a suíte de testes do `lhamalog-core` após T032–T041 e registrar o resultado no guia de integração em `../lhamalog-core/docs/usage-facts-integration.md`
- [X] T046 **Collector e Core** Executar o ensaio E2E do `quickstart.md` com logs, traces, lote parcial, reenvio, Core 503/timeout e reinício, documentando o resultado em `specs/005-tenant-product-volume/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: pode iniciar imediatamente; a aceitação operacional do RPO Redis e a rotação do token não
  alteram a implementação desta feature.
- **Phase 2**: depende da Phase 1 e bloqueia todas as histórias.
- **US1 (Phase 3)**: depende da Phase 2.
- **US2 (Phase 4)**: depende de US1, pois usa sua fábrica e medição canônica.
- **US3 (Phase 5)**: depende de US2 para obter fatos duráveis; tarefas Collector e Core podem avançar em paralelo após T029–T034.
- **Phase 6**: depende das histórias desejadas. T045 depende da implementação Core; T046 depende de T037 e T041, já definidos tecnicamente.

### User Story Dependencies

- **US1 (P1)**: implementa a medição determinística e é o MVP técnico.
- **US2 (P1)**: complementa US1 no ponto correto de reconhecimento; não é seguro entregar o reconhecimento ao exportador sem ela.
- **US3 (P2)**: usa o fato persistido pelas histórias anteriores para integração com o Core e cria o modelo de ciclo do plano.
- **Autenticação**: T037 e T041 usam token dedicado em segredo/configuração de ambiente, o cabeçalho
  `X-LhamaLog-Service-Token` e as rotas do contrato. Token Clerk e `Authorization` são vedados; rotação é
  decisão operacional pendente.

## Parallel Opportunities

- T001–T004 podem ser executadas em paralelo.
- T005–T008 podem ser executadas em paralelo antes de T009–T012.
- Em US1, T013–T016 podem ocorrer em paralelo; em US2, T021–T024 também.
- Em US3, T029–T034 podem ocorrer em paralelo e separam explicitamente Collector de Core.
- Após seus testes respectivos, T035–T036 (Collector) e T038–T040 (Core) podem ser implementadas por equipes diferentes.

## Parallel Example: User Story 3

```text
Task: "Collector: contrato HTTP em src/test/java/br/com/lhamalog/collector/usage/core/CoreUsageFactsClientContractTest.java"
Task: "Collector: retry e lease em src/test/java/br/com/lhamalog/collector/usage/core/UsageOutboxWorkerTest.java"
Task: "Core: contrato do endpoint em ../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/api/UsageFactsControllerContractTest.java"
Task: "Core: idempotência atômica em ../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/application/UsageFactIdempotencyIntegrationTest.java"
```

## Implementation Strategy

### MVP First

1. Concluir Phases 1 e 2.
2. Executar T013–T020 com testes vermelhos antes da implementação.
3. Validar US1 isoladamente: logs/traces, tenant/produto e lote parcialmente válido.
4. Concluir US2 antes de habilitar qualquer caminho de produção para a medição.

### Incremental Delivery

1. Medição canônica (US1) → validar isoladamente.
2. Reconhecimento durável após as confirmações (US2) → validar reenvio e falhas.
3. Outbox e contrato Core (US3) → validar retry e idempotência.
4. Implementar e validar autenticação de serviço (T037/T041) com valores de teste injetados.

## Notes

- Todas as tarefas obedecem ao formato de checklist: checkbox, ID sequencial, marcador `[P]` quando paralelizável, `[US#]` nas histórias e caminho de arquivo explícito.
- Os testes estão posicionados antes da implementação em cada unidade de entrega.
- A rotação do token e eventual `appendfsync always` são decisões operacionais de infraestrutura, não
  bloqueios de implementação.

## Pendências para a próxima implementação

A T046 permanece o ensaio E2E desta entrega. As correções da revisão do PR #4 do Core
foram registradas abaixo e aplicadas no worktree local, sem commit.

- [X] T046 **Collector e Core** Executar o ensaio E2E do `quickstart.md` com logs, traces, lote parcial, reenvio, Core 503/timeout e reinício, documentando o resultado em `specs/005-tenant-product-volume/quickstart.md` e `specs/005-tenant-product-volume/e2e-t046-evidence.md`
- [ ] T047 **Infra** Avaliar e, se aprovado operacionalmente, aplicar `appendfsync always` para reduzir o RPO de ~1 s do Redis AOF `everysec` após HTTP 200; Redis Cluster permanece fora de escopo
- [ ] T048 **Collector** Operacionalizar o estado `AMBIGUOUS` decorrente do Lua sem rollback (alerta, reconciliação e intervenção), sem fingir rollback transacional
- [ ] T049 **Core** Definir e integrar a fonte externa que preenche `firstPaymentDate` na entidade de plano/assinatura
- [ ] T050 **Operação** Definir e executar a política de rotação dos tokens de serviço `LHAMALOG_COLLECTOR_CORE_TOKEN` e `LHAMALOG_CORE_USAGE_TOKEN`
- [ ] T051 **Core** Executar os 4 testes `CompanyProductIntegrationTests` atualmente pulados por falta de `TEST_MONGODB_URI`

### Revisão do PR #4 do Core (worktree local, 2026-11-10)

- [X] T052 **Core** Separar idempotência com TTL de 7 dias (`UsageFactIdempotency`) do armazenamento de agregação sem TTL (`UsageFact`) em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/MongoUsageFactRepository.java`
- [X] T053 **Core** Devolver `current-cycle` separado por domínio (`logs` e `traces`) e atualizar contrato/docs em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/application/UsageFactService.java` e `specs/005-tenant-product-volume/contracts/usage-facts-api.md`
- [X] T054 **Core** Persistir `acceptedAt` como `Instant` e cobrir consulta por janela em Mongo real (Testcontainers) em `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/infrastructure/MongoUsageFactRepositoryIT.java`
- [X] T055 **Core** Criar explicitamente no startup os índices TTL e `plan_subscription_tenant_product` via `IndexOperations` em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/UsageMongoIndexInitializer.java`
- [X] T056 **Core** Tratar só duplicate key `11000`, propagar o restante, e reinserir se o documento de idempotência já tiver expirado em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/MongoUsageFactRepository.java`
- [X] T057 **Core** Sem ciclo ativo antes de `firstPaymentDate` (escolha técnica reversível para o Michel confirmar) em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/application/UsageCycle.java`
- [X] T058 **Core** Validar `from < to` somente no serviço em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/application/UsageFactService.java`
- [X] T059 **Core** Mover as implementações `InMemory` de repositório para `src/test` em `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/infrastructure/InMemoryUsageFactRepository.java` e `../lhamalog-core/src/test/java/br/com/lhamalog/core/usage/application/InMemoryPlanSubscriptionRepository.java`
- [X] T060 **Core** Separar DTO HTTP do documento Mongo com mapper em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/UsageFactDocumentMapper.java`
- [X] T061 **Core** Paginar `GET /internal/usage-facts` com `limit`/`cursor` (máximo 100) e atualizar o contrato; o Collector não consome essa consulta hoje

### Revisão do PR #5 do Collector (worktree local, 2026-11-10)

- [X] T062 **Collector** Comparar reenvio pelo conteúdo imutável do lote, sem `acceptedAt`, em `src/main/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepository.java`
- [X] T063 **Collector** Reabrir lote `ABANDONED` para medi-lo uma vez em `src/main/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepository.java`
- [X] T064 **Collector** Incluir `leaseOwner`/`leaseUntil` no CAS e em `findAcceptedReady` em `src/main/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepository.java`
- [X] T065 **Collector** Expirar `SYNCED` e `ABANDONED` terminais após 7 dias (`expireAt` + índice) em `src/main/java/br/com/lhamalog/collector/usage/outbox/MongoUsageOutboxRepository.java`
- [X] T066 **Collector** Estado `SUSPENDED` e métrica `lhamalog.usage.outbox.suspended` em `src/main/java/br/com/lhamalog/collector/usage/core/UsageOutboxWorker.java`
- [X] T067 **Collector** Serializar o fato com `JsonMapper` em `src/main/java/br/com/lhamalog/collector/usage/core/CoreUsageFactsClient.java`
- [X] T068 **Collector** Simplificar ramos redundantes do Lua e tratar `CLAIMED` no sink em `src/main/java/br/com/lhamalog/collector/messaging/AbstractStreamPublisher.java` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/PublishingLogsSink.java`
- [X] T069 **Collector** Tratar só duplicate key `11000` em `insertPending` e usar no-ops no lugar de nulos opcionais
- [X] T070 **Collector** Documentar `lhamalog.usage.*`, token obrigatório no boot e a medição em `docs/usage-measurement.md` e `README.md`
- [X] T071 **Collector** Forcar `lhamalog.usage.enabled=true` no ensaio T046 via `spring-boot.run.arguments`/`jvmArguments` (alem do env), registrar `usage.enabled` no startup e estados da outbox nas evidencias
- [X] T072 **Core** Persistir/ler `LocalDate` (`firstPaymentDate`) em UTC para o fixture `2026-11-01T00:00:00Z` nao virar o dia civil anterior em `America/Sao_Paulo` em `../lhamalog-core/src/main/java/br/com/lhamalog/core/usage/infrastructure/UsageMongoConverters.java`
- [X] T073 **Collector e Core** Reexecutar T046 e confirmar no Core contagens/bytes separados, dedup do reenvio, lote novo e retry apos Core fora (SYNCED)

