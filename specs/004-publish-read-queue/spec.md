# Feature Specification: Publicar logs na fila de leitura

**Feature Branch**: `feature/LLT-184-publish-read-queue`

**Created**: 2026-11-04

**Status**: Draft — clarificações resolvidas; pronto para plan/tasks/implement

**Input**: Jira LLT-184 "Publicar logs na fila de leitura" (Épico Coletor — OpenTelemetry → fila de leitura,
Feature E05.F2 Persistência e fila, item E05.F2.1, relacionado SSID_003). História: "Como sistema, quero colocar
cada lote aceito na fila consumida pela Varredura/Finder."

**Base já entregue (LLT-182, spec 002, ADR_146 v2)**: o conteúdo fica no OpenObserve; os Redis Streams
`lhamalog:stream:logs` (`log.ingested` v1) e `lhamalog:stream:traces` (`trace.ingested` v1) recebem uma
**referência por lote**. O envelope traz `idempotencyId` = `batchId` (determinístico, derivado dos ids dos
registros), `metadata.tenantId`, `metadata.productId`; o corpo traz `productId`, `receivedAt`, `priority` e
`criticality` derivadas da severidade. Falha do Redis hoje vira `DestinationUnavailable` (HTTP 503
`Retry-After: 5` / gRPC `UNAVAILABLE`), sem retry interno e sem DLQ.

**O que o LLT-184 acrescenta**: retry interno da publicação, fila de erro (DLQ) quando as tentativas acabam,
garantia formal de entrega pelo menos uma vez com idempotência por lote, e prioridade padrão explícita.

## Clarifications

### Session 2026-11-04

- Q1: Quais os nomes dos streams de DLQ e o `MAXLEN`? → A: `lhamalog:stream:logs:dlq` e
  `lhamalog:stream:traces:dlq`, ambos com `MAXLEN ~ 100000` (ajustável por ambiente como os streams principais).
- Q2: Quantas tentativas e qual backoff? → A: 3 tentativas totais, com esperas de 100 e 200 ms entre elas.
  A espera de 400 ms só ocorreria com 4 tentativas; o teto de espera acumulada é ~2 s. Tudo é configurável por
  variáveis `LHAMALOG_MESSAGING_*`.
- Q3: O que vai na mensagem da DLQ? → A: envelope original + `targetStream`, `attempts`, `lastError`
  (classe + mensagem, sem segredos) e `failedAt`.
- Q4: Depois de esgotar tentativas e gravar na DLQ, o que o exportador recebe? → A: HTTP 200 / gRPC OK se a
  escrita na DLQ confirmar; 503 `Retry-After: 5` / gRPC `UNAVAILABLE` só se a DLQ também falhar.
- Q5: Escopo de sinais? → A: logs e traces (mesmo comportamento via `AbstractStreamPublisher`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Cada lote aceito chega à fila pelo menos uma vez, sem duplicar efeito (Priority: P1)

Depois que o lote é guardado no OpenObserve, o Collector publica uma referência na fila de leitura. Se a mesma
mensagem for escrita mais de uma vez (retry interno ou reenvio do exportador), ela leva a mesma chave de
idempotência e a Varredura/Finder processa o lote uma única vez.

**Why this priority**: critério 1 do Jira; sem isso a Varredura perde ou duplica lotes.

**Independent Test**: publicar o mesmo lote duas vezes (retry simulado e reenvio do cliente) e conferir que
todas as entradas no stream têm o mesmo `idempotencyId`.

**Acceptance Scenarios**:

1. **Given** um lote aceito e guardado, **When** o Redis confirma o `XADD`, **Then** existe ao menos uma entrada
   no stream com `idempotencyId` igual ao `batchId` do lote.
2. **Given** o exportador reenvia o mesmo lote depois de um 503, **When** o Collector publica de novo, **Then** a
   nova entrada tem o mesmo `idempotencyId` da anterior.
3. **Given** uma falha transitória no primeiro `XADD`, **When** o retry interno tem sucesso, **Then** o cliente
   recebe sucesso e nenhuma mensagem vai para a DLQ.

---

### User Story 2 - Falha persistente de publicação vai para a fila de erro (Priority: P1)

Se o Redis não confirmar a publicação, o Collector tenta de novo com backoff. Esgotadas as tentativas, a
mensagem é registrada na fila de erro (DLQ) para reprocessamento/investigação, com o motivo da falha.

**Why this priority**: critério 2 do Jira; hoje a falha só vira 503 e nada fica registrado do lado do Collector.

**Independent Test**: com o stream principal indisponível (ou falha injetada), conferir N tentativas e a
entrada na DLQ com o envelope original e o motivo.

**Acceptance Scenarios**:

1. **Given** falha em todas as tentativas, **When** as tentativas se esgotam, **Then** a mensagem é escrita na
   DLQ com o envelope original, o stream de destino, o número de tentativas e o último erro.
2. **Given** a mensagem foi para a DLQ com sucesso, **When** o Collector responde ao exportador, **Then** a
   resposta é sucesso (HTTP 200 / gRPC OK); o lote não some sem registro.
3. **Given** a DLQ também está indisponível, **When** a escrita na DLQ falha, **Then** o Collector responde
   `DestinationUnavailable` (503 / `UNAVAILABLE`) e registra log de erro sem segredo.

---

### User Story 3 - Mensagem com tenant, produto, receivedAt e prioridade padrão (Priority: P2)

Toda mensagem da fila contém `tenantId`, `productId`, `receivedAt` e `priority`. Quando a severidade não define
prioridade, a mensagem leva a prioridade padrão.

**Why this priority**: critério 3 do Jira; já quase coberto pelo LLT-182, falta formalizar o padrão.

**Independent Test**: publicar lote sem severidade e conferir `priority` igual ao padrão.

**Acceptance Scenarios**:

1. **Given** um lote sem severidade, **When** é publicado, **Then** `priority` é a prioridade padrão
   (a de `UNSPECIFIED` já usada no 182).
2. **Given** qualquer lote, **When** é publicado, **Then** `metadata.tenantId`, `body.productId` e
   `body.receivedAt` estão presentes e não vazios.

### Edge Cases

- Pipeline parcialmente confirmado: entradas já escritas ficam; o retry reescreve com o mesmo `idempotencyId`.
- Timeout do Redis sem resposta: tratado como falha (pode ter gravado) → retry; duplicata é aceita (at-least-once).
- Lote vazio: nada é publicado nem enviado à DLQ (comportamento do 182 mantido).
- Retry não pode estourar o timeout do exportador OTLP (tempo total de tentativas limitado ao teto ~2 s).
- Collector reiniciado durante os retries: o lote não fica pendente em memória; se a resposta ainda não saiu e a
  DLQ também falhou, o exportador reenvia após 503.
- DLQ também tem `MAXLEN` aproximado (~100000) para não crescer sem limite.
- Escopo: logs e traces; métricas fora.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST publicar uma mensagem de referência por lote aceito e guardado, mantendo o modelo
  de referência do LLT-182 (ADR_146 v2); o conteúdo continua só no OpenObserve.
- **FR-002**: O sistema MUST usar `batchId` como `idempotencyId` em toda escrita da mesma mensagem (retry
  interno, reenvio do exportador e DLQ).
- **FR-003**: O sistema MUST garantir entrega pelo menos uma vez: só responde sucesso ao exportador depois que o
  Redis confirmou a entrada no stream principal **ou** na DLQ do domínio.
- **FR-004**: O sistema MUST repetir a publicação que falhar, com no máximo 3 tentativas totais e esperas de
  100 e 200 ms entre elas (a espera de 400 ms só ocorreria com 4 tentativas; teto de espera ~2 s), configuráveis
  por `LHAMALOG_MESSAGING_*`.
- **FR-005**: Esgotadas as tentativas, o sistema MUST escrever a mensagem em `lhamalog:stream:logs:dlq` ou
  `lhamalog:stream:traces:dlq` (conforme o domínio) com o envelope original, `targetStream`, `attempts`,
  `lastError` (classe + mensagem, sem segredos) e `failedAt`.
- **FR-006**: Se a escrita na DLQ falhar, o sistema MUST responder `DestinationUnavailable`
  (HTTP 503 `Retry-After: 5` / gRPC `UNAVAILABLE`), como hoje.
- **FR-007**: Toda mensagem MUST conter `tenantId`, `productId`, `receivedAt` e `priority`; sem severidade,
  `priority` MUST ser a prioridade padrão.
- **FR-008**: O comportamento MUST valer para logs e traces, reaproveitando `AbstractStreamPublisher` para não
  duplicar lógica.
- **FR-009**: O sistema MUST registrar logs de tentativas, sucessos após retry e envios à DLQ, sem conteúdo de
  telemetria nem segredo. Métricas ficam para a task futura T016, ainda não implementada.
- **FR-010**: Retry e DLQ MUST NOT mudar o formato das mensagens do stream principal (`log.ingested` v1 /
  `trace.ingested` v1); nenhuma mudança de versão de schema é esperada.
- **FR-011**: Se a DLQ confirmar depois das tentativas esgotadas, o sistema MUST tratar a publicação como
  sucesso para o sink (exportador recebe 200 / OK).

### Key Entities

- **Mensagem de referência do lote**: envelope do 182 (idempotencyId, event, origin, schemaVersion,
  publishedAt, metadata, body) apontando para o lote no OpenObserve.
- **Política de retry**: número máximo de tentativas, backoff inicial, multiplicador e teto de tempo total.
- **Mensagem de DLQ**: envelope original + stream de destino, tentativas, último erro e momento da falha.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em teste com Redis real (Testcontainers), 100% dos lotes aceitos aparecem no stream principal ou
  na DLQ; nenhum lote aceito some sem registro.
- **SC-002**: Em falha transitória que se recupera dentro das tentativas, 100% dos lotes chegam ao stream
  principal e o exportador recebe sucesso.
- **SC-003**: Todas as entradas do mesmo lote (principal, retries, DLQ) têm o mesmo `idempotencyId`.
- **SC-004**: O tempo total de retry por requisição fica abaixo do limite configurado (≤ ~2 s), sem
  estourar o timeout padrão do exportador OTLP (10 s).
- **SC-005**: 100% das mensagens têm `tenantId`, `productId`, `receivedAt` e `priority` preenchidos.

## Assumptions

- A deduplicação por `idempotencyId` é responsabilidade do consumidor (Varredura/Finder), como no 182.
- A DLQ fica no mesmo Redis do stream principal; se o Redis inteiro cair, vale FR-006 (503) e o exportador reenvia.
- Não é preciso mudar o LhamaLog Core: publicação, retry e DLQ são internos ao Collector; o Core não tem hoje
  DLQ, streams nem API de tokens/reserva relacionada.
- Reprocessamento automático da DLQ fica fora do escopo do LLT-184.
- Trabalho na branch `feature/LLT-184-publish-read-queue`.
- Prioridade padrão = criticidade `UNSPECIFIED` → `LOW` (já no LLT-182 / research D11).