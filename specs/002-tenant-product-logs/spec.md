# Feature Specification: Associar logs ao tenant e ao produto

**Feature Branch**: `feature/LLT-182-tenant-product-logs`

**Created**: 2026-11-01

**Status**: Draft

**Input**: Jira LLT-182 "Associar logs ao tenant e ao produto" (Épico Coletor, E05.F1.2, prioridade Highest),
com escopo ampliado: publicar os logs aceitos numa fila para a Varredura.

**Revisão 2026-11-02**: a fila leva só uma **referência** ao lote no OpenObserve (fonte do conteúdo), para
logs e traces. Decisões em research.md D9-D12.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Todo log recebido pertence a um tenant e a um produto (Priority: P1)

O produto cliente envia logs com o seu token. Cada log guardado e cada log entregue à Varredura leva o
`tenantId` e o `productId` que vêm do token, nunca de dados do lote. O contexto OTel (recurso, escopo e
atributos do log) chega intacto.

**Why this priority**: sem dono confiável o log não pode ser isolado por cliente nem cobrado; é o critério
central do LLT-182.

**Independent Test**: enviar um lote cujo recurso e atributos tentam se passar por outro tenant e conferir o
destino e a fila.

**Acceptance Scenarios**:

1. **Given** um token ativo do produto P do tenant T, **When** o produto envia um log por HTTP ou gRPC,
   **Then** o log guardado e a mensagem da fila têm `tenantId` T e `productId` P.
2. **Given** um lote com atributos `tenantId`/`productId` falsos no recurso e no log, **When** é aceito,
   **Then** a identidade continua a do token e os atributos falsos continuam como atributos, sem alteração.
3. **Given** um log com `service.name`, atributos de recurso, escopo e atributos próprios, **When** é aceito,
   **Then** a mensagem da fila preserva todos esses valores.

---

### User Story 2 - Logs aceitos vão para a fila da Varredura (Priority: P1)

Depois de autenticado, validado e guardado, cada lote aceito vira um evento `log.ingested` (ou
`trace.ingested`) com a referência ao OpenObserve, na fila da Varredura, no envelope padrão de mensageria da LhamaLog.

**Why this priority**: a Varredura depende desse evento para analisar os logs; é o escopo novo da história.

**Independent Test**: enviar um lote válido e ler a fila.

**Acceptance Scenarios**:

1. **Given** um lote válido, **When** o collector confirma o recebimento, **Then** existe um evento por lote
   aceito, com identificador de idempotência, nome do evento, origem, versão de esquema e instante de
   publicação.
2. **Given** um lote misto, **When** é aceito parcialmente, **Then** só os logs válidos vão para a fila.
3. **Given** o mesmo lote reenviado pelo cliente, **When** é aceito de novo, **Then** o lote e cada registro têm os
   mesmos identificadores da primeira vez.

---

### User Story 3 - Nada sem token válido ou inválido chega à fila (Priority: P1)

**Why this priority**: critério 3 do LLT-182; a fila não pode receber dados de quem não se autenticou.

**Independent Test**: enviar lotes sem token, com token revogado e com registros inválidos e conferir a fila.

**Acceptance Scenarios**:

1. **Given** um token ausente, desconhecido, revogado ou expirado, **When** o lote chega, **Then** a resposta
   é não autorizado e nada é publicado.
2. **Given** um lote em que todos os registros são inválidos, **When** chega, **Then** a resposta é rejeição
   e nada é publicado.
3. **Given** o destino de armazenamento indisponível ou recusando dados, **When** o lote chega, **Then** nada
   é publicado.

---

### User Story 4 - Fila indisponível pede nova tentativa (Priority: P2)

**Acceptance Scenarios**:

1. **Given** a fila fora do ar, **When** um lote válido chega, **Then** o collector não confirma sucesso e
   responde erro temporário (503 / `UNAVAILABLE`), para o cliente tentar de novo.

### Edge Cases

- Dois logs idênticos no mesmo lote: são eventos distintos, com identificadores distintos.
- Reenvio após falha da fila: o armazenamento recebe o lote de novo (entrega pelo menos uma vez) e a
  Varredura descarta os eventos repetidos pelo identificador.
- Recusa parcial pelo armazenamento: não dá para saber quais registros foram guardados, então nada é
  publicado (risco documentado em research.md).
- Log sem `traceId`: a mensagem não leva `traceId` nos metadados.
- Fila cheia: as entradas mais antigas são descartadas acima do limite configurado.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Todo log aceito MUST levar `tenantId` e `productId` vindos do token validado.
- **FR-002**: Dados do lote MUST NOT alterar `tenantId` nem `productId`.
- **FR-003**: Atributos de recurso, `service.name`, escopo (nome, versão, atributos) e atributos do log MUST
  ser preservados no armazenamento (OpenObserve). A fila MUST NOT levar conteúdo de log ou span.
- **FR-004**: Cada lote aceito e guardado MUST ser publicado como uma referência: `log.ingested` (versão 2)
  em `lhamalog:stream:logs` ou `trace.ingested` (versão 1) em `lhamalog:stream:traces`, origem `collector`,
  com `tenantId` apenas nos metadados, junto de `productId`, `correlationId` e `traceId` quando o lote tem
  um só trace. Campos em contracts/log-ingested-stream.md.
- **FR-011**: Cada documento no OpenObserve MUST ter `lhamalog_batch_id` e `lhamalog_record_id`; a referência
  leva organização, stream, `batchId`, faixa de `_timestamp` e, em lote de um registro, `recordId`.
- **FR-012**: Prioridade e criticidade MUST vir só da severidade (máximo do lote).
- **FR-005**: A publicação MUST acontecer só depois da autenticação, da validação e da confirmação do
  armazenamento.
- **FR-006**: Lotes não autorizados, inválidos ou não confirmados pelo armazenamento MUST NOT gerar eventos.
- **FR-007**: O identificador de idempotência MUST ser determinístico: o mesmo log no mesmo lote reenviado
  gera o mesmo identificador.
- **FR-008**: Falha da fila MUST resultar em erro temporário com pedido de nova tentativa, nunca em sucesso.
- **FR-009**: A fila MUST ter limite de tamanho padrão de 100000 entradas, ajustável por ambiente.
- **FR-010**: A fila local MUST exigir senha definida fora do repositório.

### Key Entities

- **Evento log.ingested / trace.ingested**: envelope com identificador de idempotência (o `batchId`), nome,
  origem, versão, instante de publicação, metadados (dono e correlação) e corpo (a referência ao lote).
- **Evento log.checked / trace.checked**: publicado pela Varredura (fora do collector) depois da varredura de
  dados sensíveis e da validação de prompt-injection.
- **Fila de logs**: sequência ordenada e limitada de eventos consumida pela Varredura.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% dos logs aceitos nos testes têm `tenantId` e `productId` iguais aos do token.
- **SC-002**: 0 eventos na fila para lotes não autorizados ou inválidos nos testes.
- **SC-003**: 100% das referências localizam o lote no OpenObserve; 0 conteúdo de log/span na fila.
- **SC-004**: Com a fila fora do ar, 0 respostas de sucesso; todas pedem nova tentativa.

## Assumptions

- O formato do envelope segue o rascunho de ADR de mensageria com Redis Streams (status Proposta).
- Só o lado que publica faz parte desta feature; o consumo e a deduplicação são da Varredura.
- Traces também são publicados como referência (revisão 2026-11-02).
- O armazenamento (OpenObserve) continua sendo gravado antes da fila.
