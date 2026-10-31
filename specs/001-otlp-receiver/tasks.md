---

description: "Task list for OTLP log and trace ingestion"
---

# Tasks: Recepção OTLP de logs e traces

**Input**: Design documents from `/specs/001-otlp-receiver/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/otlp-export.md, quickstart.md

**Tests**: Incluídos. A constituição exige teste local antes do merge, e o quickstart lista os cenários. Escrever o teste da história e vê-lo falhar antes da implementação correspondente.

**Organization**: Uma fase por história. Cada task leva `[IA]` ou `[Michel]`. `[Michel]` é a lógica de negócio (validade, atribuição ao produto, decisão do lote e classe do erro). `[IA]` é arranque, enquadramento do protocolo e adaptador de I/O.

## Format: `[ID] [P?] [Story] [Dono] Description`

- **[P]**: Pode correr em paralelo (outro arquivo, sem depender de task incompleta)
- **[Story]**: `[US1]`, `[US2]`, `[US3]` só nas fases de história
- **[IA]** ou **[Michel]**: quem escreve a task
- Toda task tem caminho de arquivo

## Path Conventions

Código em `src/main/java/br/com/lhamalog/collector/`. Testes em `src/test/java/br/com/lhamalog/collector/`. Pacote da feature: `ingestion/otlp/`.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Trocar o Elasticsearch pelo OpenObserve e declarar as bibliotecas do receptor

- [ ] T001 [IA] Em `pom.xml`, adicionar `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha`, importar `io.grpc:grpc-bom:1.85.1` e depender de `grpc-netty-shaded`, `grpc-protobuf`, `grpc-services` e `protobuf-java-util` sem versão própria, mais `com.github.ben-manes.caffeine:caffeine` sem versão. Remover `spring-boot-starter-data-elasticsearch`, `spring-boot-starter-data-elasticsearch-test` e `testcontainers-elasticsearch`
- [ ] T002 [P] [IA] Em `infra/local/compose.yaml`, remover o serviço `elasticsearch` e o volume `es-data`. Adicionar OpenObserve OSS `public.ecr.aws/zinclabs/openobserve` na porta 5080, com tag fixa igual à do teste, `ZO_DATA_DIR` e usuário root só por variável de ambiente, sem senha no arquivo
- [ ] T003 [P] [IA] Em `infra/local/.env.example`, trocar `ELASTICSEARCH_PORT` pelos nomes `OPENOBSERVE_URL`, `OPENOBSERVE_ORGANIZATION`, `OPENOBSERVE_STREAM`, `OPENOBSERVE_USERNAME` e `OPENOBSERVE_PASSWORD`, sem valor de segredo
- [ ] T004 [P] [IA] Em `src/main/resources/application.properties`, `application-local.properties` e `application-prod.properties`, remover `spring.elasticsearch.*`. Local: HTTP OTLP 4328 e gRPC 4327. Produção: HTTP 4318 e gRPC 4317. OpenObserve por `OPENOBSERVE_URL`, organização `lhamalog`, stream `logs` e Basic auth vinda do ambiente, sem padrão de senha em produção

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Tipos, leitura de token e portas de destino usados por todas as histórias

**⚠️ CRITICAL**: Nenhuma história começa antes do fim desta fase

- [ ] T005 [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/IngestionDecision.java` com os estados `Unauthorized` (token ausente, vazio, desconhecido, revogado ou expirado; destino não é chamado), `CatalogUnavailable` (a leitura de `products` falha; destino não é chamado), `InvalidPayload` (corpo ilegível, encoding não suportado, acima do limite, ou todos os registros inválidos; destino não é chamado), `EmptySuccess` (lote bem formado com zero registros; destino não é chamado), `Accepted` (um ou mais válidos e o destino confirmou todos; destino recebe só os válidos), `PartiallyAccepted` (há rejeitados e o destino confirmou os válidos; destino recebe só os válidos) e `DestinationUnavailable` (havia válidos e o destino não confirmou; nada é confirmado ao remetente). Falha do destino no meio do lote válido termina em `DestinationUnavailable`, mesmo que a classificação já tenha rejeitado outros registros
- [ ] T006 [P] [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/ProductCredential.java` com `productId` string obrigatório, `tokenHash` string obrigatório (SHA-256, hex minúsculo, dos bytes UTF-8 do token, único), `status` obrigatório `ACTIVE` ou `REVOKED`, e `expiresAt` instante ou ausente (ausente significa sem prazo). Válido só quando `status` é `ACTIVE` e `expiresAt` está ausente ou é posterior ao instante da requisição. Qualquer outro caso é não autorizado. Dois documentos com o mesmo hash são configuração inválida e o token é recusado. O valor do token não é campo
- [ ] T007 [P] [IA] Criar a porta `src/main/java/br/com/lhamalog/collector/ingestion/otlp/auth/TokenValidator.java`, que recebe o Bearer já extraído e devolve credencial válida, não autorizado ou catálogo indisponível, sem registrar o valor do token
- [ ] T008 [IA] Implementar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/auth/MongoProductCredentialStore.java` lendo a coleção MongoDB `products` e o cache Caffeine de 30 segundos para acerto e ausência, aplicando `ProductCredential`. Depende de T006 e T007
- [ ] T009 [P] [IA] Criar `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverProperties.java` com HTTP e gRPC habilitáveis, host, porta e limite padrão de 64 MiB, mais URL, organização, stream e credencial do OpenObserve
- [ ] T010 [P] [IA] Em `src/test/java/br/com/lhamalog/collector/TestcontainersConfiguration.java`, remover o bean do Elasticsearch e subir o OpenObserve com `GenericContainer` na mesma tag de `infra/local/compose.yaml`, além dos containers de MongoDB e Redis já existentes
- [ ] T011 [P] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/LogsSink.java`, que aceita só registros já válidos e o `productId` do token
- [ ] T012 [P] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/TracesSink.java`, que aceita só spans já válidos e o `productId` do token
- [ ] T013 [P] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/SinkUnavailableException.java` para destino ou falha de confirmação, com `Retry-After` em segundos

**Checkpoint**: Fundação pronta. As histórias podem começar

---

## Phase 3: User Story 1 - Entregar logs do produto (Priority: P1) 🎯 MVP

**Goal**: Um produto com token válido entrega logs OTLP por HTTP e por gRPC, e o OpenObserve fica com esses logs atribuídos ao produto do token

**Independent Test**: Com token válido e OpenObserve disponível, enviar um lote de logs válido em cada canal e conferir a confirmação, o `productId` do token e os registros no stream `logs`

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T014 [P] [US1] [Michel] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/domain/LogRecordAssessmentTest.java` cobrindo log válido sem body, `trace_id` presente com tamanho diferente de 16 bytes, `span_id` presente com tamanho diferente de 8 bytes e `severity_number` fora de 0..24
- [ ] T015 [P] [US1] [Michel] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/ExportLogsAcceptanceTest.java` para `POST /v1/logs` e `LogsService/Export`: lote válido confirma e aparece no OpenObserve com `productId` do token; conteúdo que nomeia outro produto não troca esse campo; lote vazio bem formado responde sucesso e não chama o destino

### Implementation for User Story 1

- [ ] T016 [P] [US1] [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/LogRecordAssessment.java`. Log inválido quando `trace_id` está presente e o tamanho é diferente de 16 bytes, quando `span_id` está presente e o tamanho é diferente de 8 bytes, ou quando `severity_number` está fora de 0..24. Log sem body continua válido. A contagem de rejeição é por registro, não por resource nem scope
- [ ] T017 [P] [US1] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/OpenObserveLogsSink.java`. `POST /api/lhamalog/logs/_json` com array de objetos. Campos: `productId` do token, sempre; `_timestamp` em microssegundos a partir do tempo do log, ou o instante de aceite se faltar; `receivedAt`; `traceId` em hex dos 16 bytes quando houver; `spanId` em hex dos 8 bytes quando houver; `severityNumber`; `body` em texto quando houver; `attributes` em texto JSON. Confirma só com HTTP 200, `failed` igual a zero e `successful` igual à quantidade enviada. Basic auth a partir do ambiente. Não registra a credencial
- [ ] T018 [US1] [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsService.java`. No caminho feliz, `Accepted` só depois da confirmação do sink e `EmptySuccess` sem chamar o sink. O `productId` gravado é o do token. Depende de T016 e T017
- [ ] T019 [US1] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpJson.java` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java` com `POST /v1/logs`, `application/x-protobuf` e `application/json`, e `Content-Encoding: gzip`. No JSON, `traceId` e `spanId` são hex e enums são inteiros. Sucesso devolve 200 sem `partial_success`. Depende de T018
- [ ] T020 [US1] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/BearerTokenInterceptor.java` e `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java` com `opentelemetry.proto.collector.logs.v1.LogsService/Export` e metadata `authorization`. Sucesso é `OK` sem `partial_success`. Depende de T018
- [ ] T021 [US1] [IA] Criar `src/main/java/br/com/lhamalog/collector/config/OtlpReceiverConfiguration.java` para subir e parar os dois servidores com as portas de `OtlpReceiverProperties`. Depende de T019 e T020

**Checkpoint**: A história 1 funciona sozinha, nos dois canais

---

## Phase 4: User Story 2 - Recusar envio inseguro, inválido ou sem destino (Priority: P1)

**Goal**: No canal de logs, token inválido, registro inválido e destino fora devolvem classes de erro diferentes, e nada recusado entra como aceito

**Independent Test**: Sem traces, exercer as três falhas em HTTP e gRPC e conferir a classe do erro e a ausência, no OpenObserve, do que foi recusado

### Tests for User Story 2

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T022 [P] [US2] [Michel] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsFailureTest.java` para `Unauthorized` sem chamar o destino, lote misto que entrega só os válidos, lote em que todos os registros são inválidos como `InvalidPayload` (não como sucesso parcial) e falha do destino como `DestinationUnavailable` mesmo com rejeitados na classificação
- [ ] T023 [P] [US2] [IA] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpStatusTest.java` para 401 com `WWW-Authenticate: Bearer`, 400, 413, 503 com `Retry-After`, 404 em `/v1/metrics` e 405 com `Allow: POST`. Mensagens de `google.rpc.Status` em inglês
- [ ] T024 [P] [US2] [IA] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcStatusTest.java` para `UNAUTHENTICATED`, `INVALID_ARGUMENT`, `RESOURCE_EXHAUSTED` sem `RetryInfo`, `UNAVAILABLE` e `UNIMPLEMENTED` no serviço de métricas

### Implementation for User Story 2

- [ ] T025 [US2] [Michel] Estender `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportLogsService.java`: autenticação antes do lote; `PartiallyAccepted` informa quantidade de registros rejeitados e o motivo; todos inválidos ou corpo ilegível viram `InvalidPayload` e não chamam o sink; destino que não confirma vira `DestinationUnavailable` e não é reportado como sucesso
- [ ] T026 [P] [US2] [IA] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/OpenObserveLogsSink.java`, HTTP diferente de 200 ou `failed` maior que zero lança `SinkUnavailableException`. Não transformar isso em sucesso parcial OTLP
- [ ] T027 [US2] [IA] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java`, mapear as decisões para 401, 400, 200 com `partial_success.rejected_log_records` e `error_message`, 413 quando o corpo descomprimido passa de 64 MiB, 503 com `Retry-After`, 404 e 405, conforme `specs/001-otlp-receiver/contracts/otlp-export.md`. Depende de T025
- [ ] T028 [US2] [IA] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java`, aplicar a mesma classe de resultado com `UNAUTHENTICATED`, `INVALID_ARGUMENT`, `OK` mais `partial_success`, `RESOURCE_EXHAUSTED` sem `RetryInfo` e `UNAVAILABLE`. Não registrar `MetricsService`. Depende de T025

**Checkpoint**: Histórias 1 e 2 passam no canal de logs, sem traces

---

## Phase 5: User Story 3 - Entregar traces do produto (Priority: P2)

**Goal**: Traces OTLP usam o mesmo token, as mesmas classes de erro e o Redis Stream `lhamalog:traces` como destino

**Independent Test**: Repetir aceite e as três falhas só com traces, em HTTP e gRPC, sem enviar logs no mesmo teste

### Tests for User Story 3

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T029 [P] [US3] [Michel] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/domain/SpanAssessmentTest.java`. Span inválido quando `trace_id` está ausente, tem tamanho diferente de 16 bytes ou é só zeros; quando `span_id` está ausente, tem tamanho diferente de 8 bytes ou é só zeros; quando `parent_span_id` está presente e tem tamanho diferente de 8 bytes; quando `name` está vazio; quando `end_time_unix_nano` é diferente de zero e anterior a `start_time_unix_nano`
- [ ] T030 [P] [US3] [Michel] Criar `src/test/java/br/com/lhamalog/collector/ingestion/otlp/ExportTracesAcceptanceTest.java` para `POST /v1/traces` e `TraceService/Export`: lote válido grava no Redis Stream `lhamalog:traces` com `productId` do token; token inválido, span inválido e Redis indisponível repetem as classes da história 2

### Implementation for User Story 3

- [ ] T031 [P] [US3] [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/domain/SpanAssessment.java` com as regras de invalidez citadas em T029
- [ ] T032 [P] [US3] [IA] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/sink/RedisTracesSink.java`. Um `XADD` por span aceito no stream `lhamalog:traces`, sem `MAXLEN`. Campos: `productId` do token, sempre; `traceId` em hex; `spanId` em hex; `name`; `receivedAt`; `payload` com o span serializado. Falha do Redis lança `SinkUnavailableException`
- [ ] T033 [US3] [Michel] Criar `src/main/java/br/com/lhamalog/collector/ingestion/otlp/application/ExportTracesService.java` reutilizando `IngestionDecision`: aceite só com confirmação, rejeição total sem chamar o sink, parcial com `rejected_spans` e falha de destino sem confirmar sucesso. Depende de T031 e T032
- [ ] T034 [US3] [IA] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/http/OtlpHttpServer.java`, aceitar `POST /v1/traces` com o mesmo mapa de status da história 2, trocando a contagem para `rejected_spans`. Depende de T033
- [ ] T035 [US3] [IA] Em `src/main/java/br/com/lhamalog/collector/ingestion/otlp/grpc/OtlpGrpcServer.java`, implementar `opentelemetry.proto.collector.trace.v1.TraceService/Export` com as mesmas classes de status. Depende de T033

**Checkpoint**: As três histórias funcionam. Traces são testáveis sem um envio de logs no mesmo teste

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentação e a verificação local pedida pela constituição

- [ ] T036 [P] [IA] Atualizar `README.md` para descrever logs no OpenObserve e traces no Redis Stream, tirar Elasticsearch da infra, do health e das variáveis, e documentar as portas 4328/4327 no local e 4318/4317 em produção, sem valor de token ou senha
- [ ] T037 [P] [IA] Conferir que `src/main/resources/application-prod.properties` não tem senha, URI com credencial ou token com valor default
- [ ] T038 [IA] Executar o roteiro de `specs/001-otlp-receiver/quickstart.md` com `.\mvnw.cmd test` e `.\mvnw.cmd clean verify` na máquina local

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
- Na Phase 2, T006, T007, T009, T010, T011, T012 e T013 podem correr juntos. T008 espera T006 e T007
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
4. História 3: traces no Redis, com as mesmas classes de erro
5. Phase 6: README e `.\mvnw.cmd clean verify`

### Parallel Team Strategy

1. Fechar Setup e fundação juntos
2. Michel fica com as tasks `[Michel]` (regras e decisões). A IA fica com as tasks `[IA]` (servidores e adaptadores), sem passar de 60% do código da feature e sem escrever a lógica de negócio sozinha
3. US2 não começa antes do export de logs da US1 existir. US3 não começa antes do mapa de status da US2

---

## Notes

- `[P]` significa outro arquivo e nenhuma task incompleta como pré-requisito
- Commit no formato `LLT-181: descricao`, em português
- Token, senha e hash de exemplo não entram em arquivo commitado. O teste gera o token na hora e grava só o hash no Mongo de teste
- A proporção 60/40 é do código produzido, conferida no review. A marca da task diz quem escreve; não é a contagem de tasks
