# Feature Specification: Publicar logs na fila de leitura

**Feature Branch**: `feature/LLT-184-publish-read-queue`

**Created**: 2026-11-04

**Status**: Implementada, em revisão — pendências operacionais e de validação abertas (não está Feito completo)

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
confirmação de escrita no principal ou na DLQ com a mesma chave de idempotência por lote, e prioridade padrão
explícita. Essa confirmação não é retenção, durabilidade nem processamento final.

## Clarifications

### Session 2026-11-04

- Q1: Quais os nomes dos streams de DLQ e o `MAXLEN`? → A: `lhamalog:stream:logs:dlq` e
  `lhamalog:stream:traces:dlq`, ambos com `MAXLEN ~ 100000` (ajustável por ambiente como os streams principais).
  O corte aproximado pode descartar entradas mais antigas sem consumo; não é retenção (ver Q9).
- Q2: Quantas tentativas e qual backoff? → A: 3 tentativas totais no stream principal, com esperas padrão de
  100 e 200 ms entre elas. A espera de 400 ms só ocorreria com 4 tentativas.
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` (padrão 2000) é o teto configurável de cada espera de backoff
  e da soma dessas esperas (`min(backoff, teto − já esperado)`). Não há deadline da requisição nem do `XADD`.
  O tempo de cada comando Redis fica fora desse teto. Variáveis `LHAMALOG_MESSAGING_*`.
- Q3: O que vai na mensagem da DLQ? → A: envelope original + `targetStream`, `attempts`, `lastError`
  (classe + mensagem, com redação de padrões conhecidos de credencial; isso não garante ausência de todo
  segredo arbitrário) e `failedAt`.
- Q4: Depois de esgotar tentativas e gravar na DLQ, o que o exportador recebe? → A: HTTP 200 / gRPC OK se a
  escrita na DLQ confirmar. Esse sucesso é a confirmação da DLQ, não o processamento final do lote.
  503 `Retry-After: 5` / gRPC `UNAVAILABLE` se a DLQ também falhar e o processo ainda responder.
- Q5: Escopo de sinais? → A: logs e traces (mesmo comportamento via `AbstractStreamPublisher`).
  `log.ingested` e `trace.ingested` permanecem na versão 1.

### Session 2026-11-08

- Q6: O teto de 2 s limita a requisição ou o `XADD`? → A: Não. Limita só as esperas de backoff (cada uma e a
  soma). Não existe deadline total da requisição nem do `XADD`.
- Q7: `lastError` elimina qualquer segredo? → A: Não. A redação cobre padrões conhecidos (userinfo de URI,
  rótulos `password` / `passwd` / `senha` / `secret` / `token` / `apikey` / `api_key` / `authorization` e
  prefixo `Bearer`). Segredo arbitrário fora desses padrões pode permanecer.
- Q8: HTTP 200 / gRPC OK depois da DLQ é processamento final? → A: Não. É só a confirmação da escrita na DLQ.
- Q9: `MAXLEN ~ 100000` garante retenção? → A: Não. O corte aproximado pode descartar entradas mais antigas
  sem consumo. Não é retenção por tempo nem durabilidade. Dono, retenção, persistência AOF/RDB, monitoramento
  e alertas da DLQ seguem sem definição.
- Q10: Timeout ambíguo, interrupção e processo morto? → A: Timeout sem resposta pode deixar referência no
  stream principal e na DLQ. O consumidor deve deduplicar por `idempotencyId` = `batchId`; essa deduplicação
  no consumidor não foi verificada nesta entrega. Interrupção e shutdown ficam como pendência de validação,
  sem mudança de código. Morte do processo antes da resposta não garante 503.

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
   resposta é sucesso (HTTP 200 / gRPC OK). Esse sucesso confirma a escrita na DLQ; não é o processamento
   final do lote pela Varredura/Finder.
3. **Given** a DLQ também está indisponível e o processo ainda responde, **When** a escrita na DLQ falha,
   **Then** o Collector responde `DestinationUnavailable` (HTTP 503 `Retry-After: 5` / gRPC `UNAVAILABLE`) e
   registra log de erro com a mesma redação de padrões conhecidos de `lastError`. Se o processo morrer antes
   de responder, essa resposta 503 não é garantida.

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
- Timeout do Redis sem resposta: tratado como falha (o comando pode ter gravado). O retry ou a DLQ pode criar
  outra referência. Um timeout ambíguo pode deixar a referência no stream principal e na DLQ. O consumidor deve
  deduplicar por `idempotencyId` = `batchId`. Esta entrega não verifica a implementação dessa deduplicação.
- Lote vazio: nada é publicado nem enviado à DLQ (comportamento do 182 mantido).
- As esperas entre tentativas usam o backoff (padrão 100 ms e 200 ms), limitado pelo teto configurável (padrão
  2000 ms) em cada espera e na soma. Não há deadline da requisição nem do `XADD`. O tempo dos comandos Redis
  não entra nesse teto; a requisição pode durar mais que 2 s. Esta spec não promete ficar abaixo do timeout do
  exportador OTLP.
- Interrupção durante a espera de backoff e shutdown: comportamento a validar na T019. Esta rodada não define
  nem promete resultado, inclusive para a escrita na DLQ ou a resposta ao exportador.
- Collector reiniciado no meio das tentativas: o lote não fica pendente em memória. Se a resposta ainda não
  saiu, o cliente pode ver queda de conexão em vez de 503.
- A DLQ usa a mesma instância Redis do stream principal. Indisponibilidade dos dois, com o processo ainda
  respondendo, resulta em 503 `Retry-After: 5` / gRPC `UNAVAILABLE`.
- `MAXLEN ~` (padrão 100000) no principal e na DLQ pode descartar entradas mais antigas ainda não consumidas.
  Não é retenção por tempo nem garantia de durabilidade. Dono, retenção, persistência AOF/RDB, monitoramento
  e alertas da DLQ ainda não estão definidos.
- Confirmação da DLQ (HTTP 200 / gRPC OK) não significa processamento final.
- Escopo: logs e traces; `log.ingested` e `trace.ingested` permanecem na versão 1. Métricas fora.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST publicar uma mensagem de referência por lote aceito e guardado, mantendo o modelo
  de referência do LLT-182 (ADR_146 v2); o conteúdo continua só no OpenObserve.
- **FR-002**: O sistema MUST usar `batchId` como `idempotencyId` em toda escrita da mesma mensagem (retry
  interno, reenvio do exportador e DLQ).
- **FR-003**: O sistema MUST só responder sucesso ao exportador depois que o Redis confirmou a entrada no stream
  principal **ou** na DLQ do domínio. Essa confirmação não impede descarte posterior por `MAXLEN ~`, não é
  processamento final e não cobre o processo que morre antes de responder.
- **FR-004**: O sistema MUST repetir a publicação que falhar, com no máximo 3 tentativas totais e esperas
  padrão de 100 e 200 ms entre elas (a espera de 400 ms só ocorreria com 4 tentativas). O teto
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` (padrão 2000) MUST limitar cada espera de backoff e a soma
  dessas esperas. Esse teto MUST NOT ser tratado como deadline da requisição nem do `XADD`. Tentativas, esperas
  e teto são configuráveis por `LHAMALOG_MESSAGING_*`.
- **FR-005**: Esgotadas as tentativas, o sistema MUST escrever a mensagem em `lhamalog:stream:logs:dlq` ou
  `lhamalog:stream:traces:dlq` (conforme o domínio) com o envelope original, `targetStream`, `attempts`,
  `lastError` e `failedAt`. `lastError` é classe + mensagem depois da redação de padrões conhecidos (userinfo
  de URI, rótulos `password` / `passwd` / `senha` / `secret` / `token` / `apikey` / `api_key` /
  `authorization` e prefixo `Bearer`). Essa redação não garante ausência de todo segredo arbitrário.
- **FR-006**: Se a escrita na DLQ falhar e o processo responder, o sistema MUST responder
  `DestinationUnavailable` (HTTP 503 `Retry-After: 5` / gRPC `UNAVAILABLE`). Principal e DLQ estão na mesma
  instância Redis: a indisponibilidade dos dois cai neste caso. Morte do processo antes da resposta não
  garante esse status.
- **FR-007**: Toda mensagem MUST conter `tenantId`, `productId`, `receivedAt` e `priority`; sem severidade,
  `priority` MUST ser a prioridade padrão.
- **FR-008**: O comportamento MUST valer para logs e traces, reaproveitando `AbstractStreamPublisher` para não
  duplicar lógica.
- **FR-009**: O sistema MUST registrar logs de tentativas, sucessos após retry e envios à DLQ, sem conteúdo de
  telemetria. O texto de erro passa pela mesma redação de padrões conhecidos de FR-005 e não garante ausência
  de todo segredo arbitrário. Métricas ficam para a task futura T016, ainda não implementada.
- **FR-010**: Retry e DLQ MUST NOT mudar o formato das mensagens do stream principal (`log.ingested` v1 /
  `trace.ingested` v1); nenhuma mudança de versão de schema é esperada.
- **FR-011**: Se a DLQ confirmar depois das tentativas esgotadas, o sistema MUST tratar a publicação como
  sucesso para o sink (exportador recebe 200 / OK). Esse sucesso é a confirmação da escrita na DLQ, não o
  processamento final do lote.

### Key Entities

- **Mensagem de referência do lote**: envelope do 182 (idempotencyId, event, origin, schemaVersion,
  publishedAt, metadata, body) apontando para o lote no OpenObserve.
- **Política de retry**: número máximo de tentativas, backoff inicial, multiplicador e teto de espera de
  backoff (não é deadline da requisição nem do `XADD`).
- **Mensagem de DLQ**: envelope original + stream de destino, tentativas, último erro e momento da falha.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em teste com Redis real (Testcontainers), 100% dos lotes aceitos aparecem no stream principal ou
  na DLQ no momento da confirmação. Isso não impede descarte posterior por `MAXLEN ~` nem cobre o processo
  que morre antes de responder.
- **SC-002**: Em falha transitória que se recupera dentro das tentativas, 100% dos lotes chegam ao stream
  principal e o exportador recebe sucesso.
- **SC-003**: Todas as entradas do mesmo lote (principal, retries, DLQ) têm o mesmo `idempotencyId`.
- **SC-004**: Com os padrões, a publicação faz no máximo 3 tentativas no stream principal e as esperas entre
  elas são 100 ms e 200 ms, cada uma e a soma limitadas pelo teto de backoff configurável (padrão 2000 ms).
  Não há deadline da requisição nem do `XADD`; o tempo dos comandos Redis fica fora desse teto.
- **SC-005**: 100% das mensagens têm `tenantId`, `productId`, `receivedAt` e `priority` preenchidos.

## Assumptions

- A deduplicação por `idempotencyId` é responsabilidade do consumidor (Varredura/Finder), como no 182. Um
  timeout ambíguo pode deixar referência no stream principal e na DLQ; o consumidor deve deduplicar por
  `idempotencyId` = `batchId`. A implementação dessa deduplicação não foi verificada nesta entrega.
- A DLQ fica no mesmo Redis do stream principal. Se o Redis inteiro cair e o processo responder, vale FR-006
  (503 `Retry-After: 5` / gRPC `UNAVAILABLE`) e o exportador pode reenviar. Morte do processo não garante 503.
- HTTP 200 / gRPC OK depois da DLQ não significa processamento final.
- `MAXLEN ~` pode descartar entradas mais antigas sem consumo. Não garante retenção temporal nem durabilidade.
  Dono, retenção, persistência AOF/RDB, monitoramento e alertas da DLQ ainda precisam de definição; esta spec
  não escolhe dono nem política.
- Não é preciso mudar o LhamaLog Core: publicação, retry e DLQ são internos ao Collector; o Core não tem hoje
  DLQ, streams nem API de tokens/reserva relacionada.
- Reprocessamento automático da DLQ fica fora do escopo do LLT-184 (task futura aberta).
- Tratamento e validação de interrupção e shutdown ficam pendentes, sem mudança de código nesta rodada.
- Trabalho na branch `feature/LLT-184-publish-read-queue`.
- Prioridade padrão = criticidade `UNSPECIFIED` → `LOW` (já no LLT-182 / research D11).
- `log.ingested` e `trace.ingested` permanecem na versão 1.