---

description: "Task list for OTLP log and trace ingestion"
---

# Tasks: Recepção OTLP de logs e traces

**Input**: Design documents from `/specs/001-otlp-receiver/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/otlp-export.md, quickstart.md

**Tests**: Incluídos. A constituição exige teste local antes do merge, e o quickstart lista os cenários. Escrever o teste da história e vê-lo falhar antes da implementação correspondente.

**Organization**: Uma fase por história. Sem marca de autoria: a IA implementa todas as tasks e o desenvolvedor revisa (constituição 2.0.0, princípio II). A lógica de negócio (validade, atribuição ao produto, decisão do lote e classe do erro) fica em `domain` e `application`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Pode correr em paralelo (outro arquivo, sem depender de task incompleta)
- **[Story]**: `[US1]`, `[US2]`, `[US3]` só nas fases de história
- Toda task tem caminho de arquivo

## Path Conventions

Código em `src/main/java/br/com/lhamalog/collector/`. Testes em `src/test/java/br/com/lhamalog/collector/`. Pacote da feature: `ingestion/otlp/`.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Trocar o Elasticsearch pelo OpenObserve e declarar as bibliotecas do receptor

- [x] T001 Em `pom.xml`, adicionar `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha`, importar `io.grpc:grpc-bom:1.85.1` e depender de `grpc-netty-shaded`, `grpc-protobuf` e `grpc-services` sem versão própria, importar `com.google.protobuf:protobuf-bom:4.36.2` (o runtime exigido por `opentelemetry-proto` 1.11.1-alpha; o `grpc-bom` não gerencia `protobuf-java-util`) e depender de `protobuf-java-util` sem versão própria, mais `com.github.ben-manes.caffeine:caffeine` sem versão. Remover `spring-boot-starter-data-elasticsearch`, `spring-boot-starter-data-elasticsearch-test` e `testcontainers-elasticsearch`. Redis fica (`spring-boot-starter-data-redis`): é a fila da Varredura, fora desta feature
- [x] T002 [P] Em `infra/local/compose.yaml`, remover o serviço `elasticsearch` e o volume `es-data`. Manter o serviço `redis` (fila da Varredura). Adicionar OpenObserve OSS `public.ecr.aws/zinclabs/openobserve:v1.1.2` na porta 5080, com tag fixa igual à do teste, `ZO_DATA_DIR` e usuário root só por variável de ambiente, sem senha no arquivo
- [x] T003 [P] Em `infra/local/.env.example`, trocar `ELASTICSEARCH_PORT` pelos nomes `OPENOBSERVE_URL`, `OPENOBSERVE_ORGANIZATION`, `OPENOBSERVE_LOGS_STREAM`, `OPENOBSERVE_TRACES_STREAM`, `OPENOBSERVE_USERNAME` e `OPENOBSERVE_PASSWORD`, sem valor de segredo
- [x] T004 [P] Em `src/main/resources/application.properties`, `application-local.properties` e `application-prod.properties`, remover `spring.elasticsearch.*`. Local: HTTP OTLP 4328 e gRPC 4327. Produção: HTTP 4318 e gRPC 4317. OpenObserve por `OPENOBSERVE_URL`, organização `lhamalog`, stream de logs `logs`, stream de traces `traces` e Basic auth vinda do ambiente, sem padrão de senha em produção

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Tipos, leitura de token e portas de destino usados por todas as histórias

**⚠️ CRITICAL**: Nenhuma história começa antes do fim desta fase

- [ ] T005 Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/IngestionDecision.java` com os estados `Unauthorized` (token ausente, vazio, desconhecido, revogado, expirado ou de produto inexistente; destino não é chamado), `CatalogUnavailable` (a leitura de `product_tokens` ou `products` falha; destino não é chamado), `InvalidPayload` (corpo ilegível, encoding não suportado, acima do limite, ou todos os registros inválidos; destino não é chamado), `EmptySuccess` (lote bem formado com zero registros; destino não é chamado), `Accepted` (um ou mais válidos e o destino confirmou todos; destino recebe só os válidos), `PartiallyAccepted` (há rejeitados e o destino confirmou os válidos; destino recebe só os válidos) e `DestinationUnavailable` (havia válidos e o destino não confirmou; nada é confirmado ao remetente). Falha do destino no meio do lote válido termina em `DestinationUnavailable`, mesmo que a classificação já tenha rejeitado outros registros
- [ ] T006 [P] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/ProductToken.java`, substituindo `ProductCredential`, para o documento da coleção `product_tokens` (relação `products` 1:N `product_tokens` por `productId`). Campos: `id` obrigatório; `productId` string obrigatório, referência a `products` (cada produto pertence a um tenant: `products.tenantId` obrigatório); `tokenHash` string obrigatório (SHA-256, hex minúsculo, dos bytes UTF-8 do token, único); `status` obrigatório `ACTIVE` ou `REVOKED`; `createdAt` instante obrigatório; `revokedAt` instante preenchido só na revogação (ausente se ativo); `expiresAt` instante opcional (ausente significa sem prazo). Invariantes: `REVOKED` exige `revokedAt`; `ACTIVE` não tem `revokedAt`. Regra de ciclo de vida documentada no tipo: token nunca é apagado fisicamente, revogar só muda `status` para `REVOKED` e preenche `revokedAt`, exclusão física só em cascata quando o product relacionado é apagado; o collector não cadastra, não rotaciona e não revoga. O valor do token não é campo
- [x] T007 [P] Criar a porta `src/main/java/br/com/lhamalog/collector/ingestion/otlp/auth/TokenValidator.java`, que recebe o Bearer já extraído e devolve `TokenValidationResult` (`src/main/java/br/com/lhamalog/collector/ingestion/otlp/auth/TokenValidationResult.java`): `Valid(tenantId, productId)`, `Unauthorized` ou `CatalogUnavailable`, sem registrar o valor do token. A porta não depende de `ProductToken` (T006) nem de `IngestionDecision` (T005), para compilar sem depender do modelo de documento
- [ ] T008 Implementar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/auth/MongoProductTokenStore.java`: hash SHA-256 do Bearer, busca por `tokenHash` na coleção MongoDB `product_tokens`, consulta do produto em `products` para obter o `tenantId`, devolvendo `TokenValidationResult.Valid(tenantId, productId)` e cache Caffeine de 30 segundos para acerto e ausência. Só leitura. Falha do MongoDB vira catálogo indisponível. Delega a decisão de validade a `ProductTokenValidity` (T008a). Não registra o valor do token. Depende de T006, T007 e T008a
- [ ] T008a [P] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/ProductTokenValidity.java` com a regra de validade: válido só quando `status` é `ACTIVE`, `revokedAt` está ausente, `expiresAt` está ausente ou é posterior ao instante da requisição e o product relacionado existe com `tenantId` preenchido. Mais de um documento com o mesmo `tokenHash` é configuração inválida e o token é recusado. Qualquer outro caso é não autorizado, sem expor o motivo ao remetente. Inclui o teste unitário `src/test/java/br/com/lhamalog/collector/ingestion/otlp/domain/ProductTokenValidityTest.java`. Depende de T006
- [x] T009 [P] Criar `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverProperties.java` com HTTP e gRPC habilitáveis, host, porta e limite padrão de 64 MiB, mais URL, organização, streams de logs e de traces e credencial do OpenObserve
- [x] T010 [P] Em `src/test/java/br/com/lhamalog/collector/TestcontainersConfiguration.java`, remover o bean do Elasticsearch e subir o OpenObserve com `GenericContainer` na mesma tag de `infra/local/compose.yaml`, além do container de MongoDB já existente. O teste insere o documento em `products` e o token (só o hash) em `product_tokens`, nunca `tokenHash` dentro de `products`. O container de Redis continua (fila da Varredura), mas não é destino de traces
- [x] T011 [P] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/LogsSink.java`, que aceita só registros já válidos, o `tenantId` do produto e o `productId` do token
- [x] T012 [P] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/TracesSink.java`, que aceita só spans já válidos, o `tenantId` do produto e o `productId` do token
- [x] T013 [P] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/SinkUnavailableException.java` para destino ou falha de confirmação, com `Retry-After` em segundos

**Checkpoint**: Fundação pronta. As histórias podem começar

---

## Phase 3: User Story 1 - Entregar logs do produto (Priority: P1) 🎯 MVP

**Goal**: Um produto com token válido entrega logs OTLP por HTTP e por gRPC, e o OpenObserve fica com esses logs atribuídos ao produto do token

**Independent Test**: Com token válido e OpenObserve disponível, enviar um lote de logs válido em cada canal e conferir a confirmação, o `productId` do token e os registros no stream `logs`

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T014 [P] [US1] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/domain/LogRecordAssessmentTest.java` cobrindo log válido sem body, `trace_id` presente com tamanho diferente de 16 bytes, `span_id` presente com tamanho diferente de 8 bytes e `severity_number` fora de 0..24
- [ ] T015 [P] [US1] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/ExportLogsAcceptanceTest.java` para `POST /v1/logs` e `LogsService/Export`: lote válido confirma e aparece no OpenObserve com `productId` do token; conteúdo que nomeia outro produto não troca esse campo; lote vazio bem formado responde sucesso e não chama o destino

### Implementation for User Story 1

- [ ] T016 [P] [US1] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/LogRecordAssessment.java`. Log inválido quando `trace_id` está presente e o tamanho é diferente de 16 bytes, quando `span_id` está presente e o tamanho é diferente de 8 bytes, ou quando `severity_number` está fora de 0..24. Log sem body continua válido. A contagem de rejeição é por registro, não por resource nem scope
- [ ] T017 [P] [US1] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/OpenObserveLogsSink.java`. `POST /api/lhamalog/logs/_json` com array de objetos. Campos: `tenantId` do produto e `productId` do token, sempre, no primeiro nível e sem substituição pelo lote; `_timestamp` em microssegundos a partir do tempo do log, ou o instante de aceite se faltar; `receivedAt`; `traceId` em hex dos 16 bytes quando houver; `spanId` em hex dos 8 bytes quando houver; `severityNumber`; `body` em texto quando houver; `attributes` em texto JSON. Confirma só com HTTP 200, `failed` igual a zero e `successful` igual à quantidade enviada. Basic auth a partir do ambiente. Não registra a credencial
- [ ] T018 [US1] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsService.java`. No caminho feliz, `Accepted` só depois da confirmação do sink e `EmptySuccess` sem chamar o sink. O `productId` gravado é o do token. Depende de T016 e T017
- [ ] T019 [US1] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpJson.java` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java` com `POST /v1/logs`, `application/x-protobuf` e `application/json`, e `Content-Encoding: gzip`. No JSON, `traceId` e `spanId` são hex e enums são inteiros. Sucesso devolve 200 sem `partial_success`. Depende de T018
- [ ] T020 [US1] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/BearerTokenInterceptor.java` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java` com `opentelemetry.proto.collector.logs.v1.LogsService/Export` e metadata `authorization`. Sucesso é `OK` sem `partial_success`. Depende de T018
- [ ] T021 [US1] Criar `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverConfiguration.java` para subir e parar os dois servidores com as portas de `OtlpReceiverProperties`. Depende de T019 e T020

**Checkpoint**: A história 1 funciona sozinha, nos dois canais

---

## Phase 4: User Story 2 - Recusar envio inseguro, inválido ou sem destino (Priority: P1)

**Goal**: No canal de logs, token inválido, registro inválido e destino fora devolvem classes de erro diferentes, e nada recusado entra como aceito

**Independent Test**: Sem traces, exercer as três falhas em HTTP e gRPC e conferir a classe do erro e a ausência, no OpenObserve, do que foi recusado

### Tests for User Story 2

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T022 [P] [US2] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsFailureTest.java` para `Unauthorized` sem chamar o destino, lote misto que entrega só os válidos, lote em que todos os registros são inválidos como `InvalidPayload` (não como sucesso parcial) e falha do destino como `DestinationUnavailable` mesmo com rejeitados na classificação
- [ ] T023 [P] [US2] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpStatusTest.java` para 401 com `WWW-Authenticate: Bearer`, 400, 413, 503 com `Retry-After`, 404 em `/v1/metrics` e 405 com `Allow: POST`. Mensagens de `google.rpc.Status` em inglês
- [ ] T024 [P] [US2] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcStatusTest.java` para `UNAUTHENTICATED`, `INVALID_ARGUMENT`, `RESOURCE_EXHAUSTED` sem `RetryInfo`, `UNAVAILABLE` e `UNIMPLEMENTED` no serviço de métricas

### Implementation for User Story 2

- [ ] T025 [US2] Estender `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsService.java`: autenticação antes do lote; `PartiallyAccepted` informa quantidade de registros rejeitados e o motivo; todos inválidos ou corpo ilegível viram `InvalidPayload` e não chamam o sink; destino que não confirma vira `DestinationUnavailable` e não é reportado como sucesso
- [ ] T026 [P] [US2] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/OpenObserveLogsSink.java`, HTTP diferente de 200 ou `failed` maior que zero lança `SinkUnavailableException`. Não transformar isso em sucesso parcial OTLP
- [ ] T027 [US2] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java`, montar a resposta HTTP a partir do status escolhido por `OtlpHttpStatusMapper` (T027a): corpo `google.rpc.Status` ou `Export*ServiceResponse`, headers `WWW-Authenticate: Bearer`, `Retry-After` e `Allow: POST`, `Content-Type` igual ao da requisição, corte em 64 MiB do corpo descomprimido, e 404/405 de roteamento, conforme `specs/001-otlp-receiver/contracts/otlp-export.md`. Depende de T025 e T027a
- [ ] T027a [US2] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpStatusMapper.java` com o mapa decisão→status HTTP: `Unauthorized` 401, `InvalidPayload` 400 (413 quando o motivo é tamanho acima do limite), `EmptySuccess` e `Accepted` 200 sem `partial_success`, `PartiallyAccepted` 200 com `partial_success.rejected_log_records` (ou `rejected_spans`) e `error_message`, `CatalogUnavailable` e `DestinationUnavailable` 503 com `Retry-After`. Depende de T005 e T025
- [ ] T028 [US2] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java`, aplicar a mesma classe de resultado com `UNAUTHENTICATED`, `INVALID_ARGUMENT`, `OK` mais `partial_success`, `RESOURCE_EXHAUSTED` sem `RetryInfo` e `UNAVAILABLE`. Não registrar `MetricsService`. Depende de T025

**Checkpoint**: Histórias 1 e 2 passam no canal de logs, sem traces

---

## Phase 5: User Story 3 - Entregar traces do produto (Priority: P2)

**Goal**: Traces OTLP usam o mesmo token, as mesmas classes de erro e o stream OpenObserve de traces (padrão `traces`, organização `lhamalog`) como destino, conforme a ADR_168

**Independent Test**: Repetir aceite e as três falhas só com traces, em HTTP e gRPC, sem enviar logs no mesmo teste

### Tests for User Story 3

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T029 [P] [US3] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/domain/SpanAssessmentTest.java`. Span inválido quando `trace_id` está ausente, tem tamanho diferente de 16 bytes ou é só zeros; quando `span_id` está ausente, tem tamanho diferente de 8 bytes ou é só zeros; quando `parent_span_id` está presente e tem tamanho diferente de 8 bytes; quando `name` está vazio; quando `end_time_unix_nano` é diferente de zero e anterior a `start_time_unix_nano`
- [ ] T030 [P] [US3] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/ExportTracesAcceptanceTest.java` para `POST /v1/traces` e `TraceService/Export`: lote válido grava no stream OpenObserve `traces` com `productId` do token; token inválido, span inválido e OpenObserve indisponível repetem as classes da história 2

### Implementation for User Story 3

- [ ] T031 [P] [US3] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/SpanAssessment.java` com as regras de invalidez citadas em T029
- [ ] T032 [P] [US3] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/OpenObserveTracesSink.java`, substituindo o antigo `RedisTracesSink`. `POST /api/lhamalog/{stream}/_json` com stream de traces configurável (padrão `traces`) e array de objetos. Campos: `tenantId` do produto e `productId` do token, sempre, no primeiro nível e sem substituição pelo lote; `_timestamp` em microssegundos a partir do início do span, ou o instante de aceite se faltar; `receivedAt`; `traceId` e `spanId` em hex; `parentSpanId` em hex quando houver; `name`; `startTimeUnixNano`; `endTimeUnixNano`; `attributes` em texto JSON. Mesma regra de confirmação dos logs: só HTTP 200, `failed` igual a zero e `successful` igual à quantidade enviada; qualquer outro caso lança `SinkUnavailableException`. Basic auth a partir do ambiente. Não registra a credencial
- [ ] T033 [US3] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportTracesService.java` reutilizando `IngestionDecision`: aceite só com confirmação, rejeição total sem chamar o sink, parcial com `rejected_spans` e falha de destino sem confirmar sucesso. Depende de T031 e T032
- [ ] T034 [US3] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java`, aceitar `POST /v1/traces` usando o mesmo `OtlpHttpStatusMapper` (T027a), trocando a contagem para `rejected_spans`. Destino de traces indisponível é o OpenObserve fora, não o Redis. Depende de T033
- [ ] T035 [US3] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java`, implementar `opentelemetry.proto.collector.trace.v1.TraceService/Export` com as mesmas classes de status, inclusive `UNAVAILABLE` quando o OpenObserve de traces não confirma. Depende de T033

**Checkpoint**: As três histórias funcionam. Traces são testáveis sem um envio de logs no mesmo teste

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentação e a verificação local pedida pela constituição

- [ ] T036 [P] Atualizar `README.md` para descrever logs e traces no OpenObserve (streams `logs` e `traces`, organização `lhamalog`, ADR_168), tokens na coleção `product_tokens` ligada a `products`, explicar que o Redis segue como fila da Varredura e não é destino do collector, tirar Elasticsearch da infra, do health e das variáveis, e documentar as portas 4328/4327 no local e 4318/4317 em produção, sem valor de token ou senha
- [ ] T037 [P] Conferir que `src/main/resources/application-prod.properties` não tem senha, URI com credencial ou token com valor default
- [ ] T038 Executar o roteiro de `specs/001-otlp-receiver/quickstart.md` com `.\mvnw.cmd test` e `.\mvnw.cmd clean verify` na máquina local

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: começa agora
- **Foundational (Phase 2)**: depende do Setup e bloqueia todas as histórias
- **User Stories (Phase 3+)**: dependem da Phase 2
- **Polish (Phase 6)**: depende das histórias que entrarem no incremento

### User Story Dependencies

- **User Story 1 (P1)**: só depende da Phase 2. É o MVP
- **User Story 2 (P1)**: depende da Phase 2 e estende o export de logs da US1 (`ExportLogsService`, servidores HTTP e gRPC). O teste da história não envia traces
- **User Story 3 (P2)**: depende da Phase 2 e reutiliza o mapa de status da US2 nos mesmos servidores. O teste da história não envia logs

### Within Each User Story

- O teste da história fica vermelho antes da implementação
- Modelo antes do caso de uso
- Caso de uso antes do HTTP e do gRPC
- A história fecha antes da próxima prioridade

### Parallel Opportunities

- T002, T003 e T004 podem correr juntos, e em paralelo com T001
- Na Phase 2, T006, T007, T009, T010, T011, T012 e T013 podem correr juntos. T008a espera T006. T008 espera T006, T007 e T008a
- Depois da Phase 2, US1 começa. US2 espera o export de logs da US1. US3 espera o mapa de status da US2
- Os testes marcados `[P]` de uma mesma história podem correr juntos

---

## Parallel Example: User Story 1

```text
T014 LogRecordAssessmentTest.java
T015 ExportLogsAcceptanceTest.java

T016 LogRecordAssessment.java
T017 OpenObserveLogsSink.java
```

Depois de T016 e T017, T018. Depois de T018, T019 e T020 juntos. T021 por último.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1 e Phase 2
2. Completar Phase 3
3. Parar e validar a história 1 nos dois canais
4. Só então seguir para a história 2

### Incremental Delivery

1. Setup + fundação
2. História 1: logs autenticados no OpenObserve
3. História 2: recusas no canal de logs
4. História 3: traces no OpenObserve, com as mesmas classes de erro
5. Phase 6: README e `.\mvnw.cmd clean verify`

### Parallel Team Strategy

1. Fechar Setup e fundação juntos
2. A IA implementa todas as tasks na ordem deste arquivo; o desenvolvedor revisa cada fase, começando por `domain` e `application`
3. US2 não começa antes do export de logs da US1 existir. US3 não começa antes do mapa de status da US2

---

## Notes

- `[P]` significa outro arquivo e nenhuma task incompleta como pré-requisito
- Commit no formato `LLT-181: descricao`, em português
- Token, senha e hash de exemplo não entram em arquivo commitado. O teste gera o token na hora e grava só o hash no Mongo de teste
- Isolamento por `tenantId` (ADR_121/ADR_168): o `tenantId` vem de `products` pelo produto do token (T006, T008, T008a) e é gravado pelos sinks (T011, T012, T017, T032)
- A regra 60/40 foi derrubada em 2026-10-31 (constituição 2.0.0). As divisões T008/T008a e T027/T027a ficam por clareza de responsabilidade, não por autoria
