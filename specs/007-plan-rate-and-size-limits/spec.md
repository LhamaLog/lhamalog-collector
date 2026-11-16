# Feature Specification: Limites de taxa e tamanho de lote por plano

**Feature Branch**: `007-plan-rate-and-size-limits`

**Created**: 2026-11-11

**Status**: Clarified (parcial) — 16/11/2026; D4/D5 resolvidos na sessão (2); Q13/Q14 ainda abertos

**Input**: User description: "Aplicar limites de taxa e tamanho máximo de lote por plano (tenant), com resposta de retentativa (429/RESOURCE_EXHAUSTED) para o exporter. Taxa e tamanho por plano; sem repetir reserva de saldo da LLT-186/spec006."

**Jira**: LLT-183 — "Aplicar limites de taxa e tamanho por plano" (status Fazendo, prioridade Alta). Épico "Coletor — OpenTelemetry → fila de leitura". Feature Endpoint OTLP E05.F1, item E05.F1.3 do LhamaLog_backlog_pendente.docx (14/10/2026). Relacionado: — (nenhum vínculo no Jira).

## Fontes

Fatos de origem (somente leitura; não inventar valores ausentes):

- **LLT-183 (Jira)**: rate limit e tamanho máximo de lote por tenant, com resposta de retentativa (429 / RESOURCE_EXHAUSTED) para o exporter.
- **ADR_131 (TabelaPrecificacao, aceita, 11/10/2026)**: campos incluem `rateLimit`, mas a coluna "RateLimite" da planilha "Plano de Monetização" (aba Página1) está **vazia** para todos os planos → `rateLimit` **[PENDENTE]** no Core. Valores de runtime desta feature vêm de configuração do Collector até a tabela ser preenchida.
- **Planilha "Plano de Monetização v4 - OpenObserve MGC" (aba Planos Ajustado, 26/10/2026)**: FREE 1 GB/mês (bloqueia ao atingir franquia, sem excedente), 1 produto, 1 membro; Starter 10 GB/mês, excedente R$ 6,00/GB, 3 produtos; Pro 100 GB/mês, excedente R$ 4,50/GB, 10 produtos; Business 500 GB/mês, excedente R$ 3,50/GB, produtos ilimitados; Enterprise 1000 GB/mês, excedente R$ 2,80/GB, ilimitados. **Não há** valores de taxa (req/s) nem de tamanho máximo de lote nas planilhas v2–v4.
- **ADR_164 (custo do MVP)**: 2 VMs Magalu, nó único, sem HA; Redis (Streams) na VM-A junto com Core/Collector; capacidade estimada ~192 GB brutos ingeridos por mês no total (todas as contas).
- **ADR_163 rev. 4 (Asaas, proposta)**: assinatura **SUSPENSA** bloqueia ingestão; INADIMPLENTE com menção a 15 dias de graça — comportamento de ingestão para INADIMPLENTE **[A CONFIRMAR / NEEDS CLARIFICATION]**.
- **ADR_108 / ADR_119**: OTLP HTTP 4318 e gRPC 4317; produto resolvido pelo token.
- **ADR_043 / ADR_050**: upload manual 100 MB/arquivo — **fora** do escopo OTLP desta feature.
- **Decisões do Michel em 16/11/2026 via BimBot Escola de TI**: D1–D9 (ver Clarifications).

## Separação de escopo (obrigatória)

| Spec / ticket | Cobre | Não cobre |
|---------------|-------|-----------|
| **spec006 / LLT-186** | Reserva de saldo de bytes do ciclo, 429 por saldo com Retry-After **120**, Core/plano fora → 503 UNAVAILABLE, lote acima do saldo recusado inteiro, refund, alerta SALDO_BAIXO | Taxa por janela; tamanho máximo de lote de aplicação (5 MB); status SUSPENSA/excedente |
| **Esta spec / LLT-183** | (a) **TAXA** — 20 req/s por tenant (Retry-After 5 s; 5 violações/60 s → bloqueio 5 min com Retry-After 300 s); (b) **TAMANHO** — lote máximo 5 MB (além do teto técnico 64 MB); (c) ordem **taxa → tamanho → reserva**; (d) assinatura SUSPENSA → 403; (e) flag de excedente por plano; (f) Core indisponível/sem plano → 503 + Retry-After **300 s**, até **5** tentativas por lote; 6ª → drop não retentável (500/INTERNAL); (g) observabilidade distinguindo taxa vs tamanho vs saldo | Cobrança/faturamento do excedente; upload manual; envelope DevOps Capacity |

## Fatos existentes no Collector (código em Development neste worktree)

Limites técnicos **globais** (não por plano) já presentes:

- `lhamalog.otlp.max-message-size` / `OTLP_RECEIVER_MAX_MESSAGE_SIZE`, padrão **64MB**, medido após descompressão; validado > 0 e ≤ 2.147.483.647 bytes.
- **OTLP/HTTP**: corpo acima do limite → HTTP **413** com status OTLP `RESOURCE_EXHAUSTED`; `Content-Length` acima do limite recusado sem ler o corpo (não-gzip).
- **OTLP/gRPC** (Netty): `maxInboundMessageSize(maxMessageBytes)` → mensagem acima do limite → **RESOURCE_EXHAUSTED** sem `RetryInfo`.
- Spec006: saldo insuficiente → HTTP **429** / gRPC `RESOURCE_EXHAUSTED` com Retry-After **120**; Core indisponível / sem plano → HTTP **503** / gRPC `UNAVAILABLE` com Retry-After **120** (esta feature altera só o caminho Core/sem plano para default **300**).

Esta feature **não substitui** o teto global de 64MB: o limite de aplicação de 5 MB MUST ser respeitado **em conjunto** com o teto global (o menor dos dois vence).

## Clarifications

### Session 2026-11-11

Sessão de clarify aberta em **11/11/2026**. Respostas pendentes do Michel naquele dia.

### Sessão 16/11/2026 (decisões do Michel via BimBot)

Registro: **Decidido pelo Michel em 16/11/2026 via BimBot Escola de TI**.

- **Q1 / D1 (valores de taxa)**: 20 requisições/segundo por tenant (empresa). Fonte de runtime no MVP: propriedade configurável do Collector (TabelaPrecificacao/`rateLimit` no Core permanece vazia — sem inventar preenchimento).
- **Q2 / D1 (unidade/algoritmo)**: requisições por segundo; janela fixa de 1 segundo (detalhe de algoritmo = decisão técnica do dev; ver plan/research).
- **Q3 / D1 (escopo)**: por tenant, somando todos os produtos.
- **Q4 / D2**: logs e traces compartilham o mesmo limite/contador de taxa.
- **Q5 / D3**: lote máximo **5 MB**; lote maior é recusado **inteiro** com HTTP **413** (gRPC **RESOURCE_EXHAUSTED**, padrão do teto global).
- **Q6 / D4 (Core indisponível)**: ver **Sessão 16/11/2026 (2)** — resolvido.
- **Q7 / D9**: contadores de taxa no **Redis**.
- **Q8 / D5 (Retry-After da taxa)**: ver **Sessão 16/11/2026 (2)** — resolvido.
- **Q9 / D6 (SUSPENSA)**: assinatura SUSPENSA → HTTP **403** (gRPC **PERMISSION_DENIED**). **INADIMPLENTE** → **[NEEDS CLARIFICATION]** (ADR_163 menciona 15 dias de graça; não decidido).
- **Q10 (observabilidade)**: não decidido pelo Michel. Decisão técnica do dev para MVP: métricas + log estruturado distinguindo taxa / tamanho / saldo / suspensão / Core fora; alerta in-app ao Dono/Admin **fora** desta entrega.
- **Q11 / D7 (excedente)**: excedente permitido e cobrado à parte **somente** se habilitado por plano (`overageEnabled`); senão mantém o corte ao zerar da spec006. Cobrança em si **fora de escopo**.
- **Q12 / D8 (ordem)**: **taxa primeiro**, depois tamanho, depois saldo/reserva (fluxo spec006). *(Recomendação técnica anterior era tamanho→taxa→reserva; sobrescrita pela decisão do Michel.)*

### Sessão 16/11/2026 (2) — D4/D5 confirmados

Registro: **Decidido pelo Michel em 16/11/2026 via BimBot Escola de TI**.

- **D4 / Core indisponível**: recusar com Retry-After **300 s**, até **5 tentativas por lote** (25 min no total); depois o lote é **perdido**.
  - **Decisão técnica do dev**: identidade do lote = `reservationId` determinístico da LLT-186/`CanonicalQuotaGate` (`quota:` + SHA-256(tenant, product, domain, bytes canônicos)) — estável entre retries do exporter do mesmo lote. Contador Redis `INCR lhamalog:quota:core-unavailable:{batchKey}` com TTL `lhamalog.quota.unavailable-attempt-ttl` (default 30m). Tentativas 1..5 → 503/UNAVAILABLE + Retry-After 300; tentativa 6+ → HTTP **500** / gRPC **INTERNAL** (não retentável OTLP), sem Retry-After, mensagem `core unavailable: retry budget exhausted, batch dropped`, métrica `reason=quota_unavailable_exhausted`. **NO_PLAN** conta no orçamento (mesmo caminho 503 da spec006). Redis down no contador → 503 + Retry-After 300 sem orçamento + WARN/métrica. Core de volta → processa e apaga a chave.
- **D5 / taxa**: cada req acima de 20/s → 429 / RESOURCE_EXHAUSTED com Retry-After **5 s** (`lhamalog.rate-limit.retry-after`). Se o tenant exceder o limite **5 vezes em 1 minuto** → **bloqueio de 5 min**; durante o bloqueio toda req → 429 com Retry-After **300 s**. A 5ª violação na janela de 60 s já responde com Retry-After 300; reqs no bloqueio **não** contam na janela de 1 s. Motivos: `rate_limit` e `rate_limit_blocked`.

**Perguntas ainda abertas**:

- **Q13 — Redis indisponível para o rate limiter**: fail-open vs fail-closed. Recomendação técnica do dev: fail-open com métrica/WARN. Comportamento provisório configurável, marcado **[A CONFIRMAR]**.
- **Q14 — INADIMPLENTE**: bloqueia como SUSPENSA (403), permite com graça de 15 dias (ADR_163), ou outro? **[NEEDS CLARIFICATION]**.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Aplicar limite de taxa do tenant com retentativa (Priority: P1) 🎯 MVP

O tenant que excede **20 requisições/segundo** (logs + traces, todos os produtos) recebe recusa com 429 / RESOURCE_EXHAUSTED e Retry-After **5 s**; após **5 violações em 60 s** fica bloqueado **5 min** com Retry-After **300 s**. A checagem ocorre **após autenticação** (tenant conhecido) e **antes** de tamanho e reserva (D8).

**Why this priority**: O ticket exige rate limit por tenant com resposta de retentativa; protege a capacidade compartilhada do MVP; vem primeiro na ordem decidida.

**Independent Test**: Com limite 20 req/s, enviar ≤ 20 e > 20 na mesma janela de 1 s; conferir aceite vs 429/RESOURCE_EXHAUSTED + Retry-After 5; forçar 5 violações e conferir bloqueio com Retry-After 300; métricas `rate_limit` vs `rate_limit_blocked` vs saldo.

**Acceptance Scenarios**:

1. **Given** um tenant abaixo de 20 req/s na janela corrente de 1 s, **When** envia um lote válido, **Then** a checagem de taxa passa.
2. **Given** um tenant que já esgotou as 20 req/s da janela, **When** envia novo lote, **Then** a resposta é 429 / RESOURCE_EXHAUSTED com Retry-After = `lhamalog.rate-limit.retry-after` (default **5 s**), sem publicar o lote e sem chamar reserva.
3. **Given** 5 violações de taxa dentro de 60 s, **When** a 5ª ocorre (ou qualquer req durante o bloqueio), **Then** 429 / RESOURCE_EXHAUSTED com Retry-After = `lhamalog.rate-limit.block-retry-after` (default **300 s**); reqs no bloqueio não incrementam a janela de 1 s.
4. **Given** a mesma política, **When** o envio é por gRPC em vez de HTTP, **Then** a classe de resultado e a retentativa são equivalentes às do HTTP.
5. **Given** logs e traces do mesmo tenant, **When** a soma das requisições OTLP na janela atinge 20, **Then** qualquer sinal adicional na mesma janela é recusado por taxa.

---

### User Story 2 - Recusar lote acima do tamanho máximo (Priority: P1)

O produto envia um lote OTLP (logs ou traces) autenticado. Se o tamanho do lote ultrapassa 5 MB (ou o teto técnico global do servidor, o que for menor), o collector recusa o envio inteiro com HTTP 413 / gRPC RESOURCE_EXHAUSTED, sem publicar na fila de leitura e sem consumir reserva de saldo da spec006. A checagem de tamanho ocorre **depois** da taxa e **antes** da reserva.

**Why this priority**: Sem teto de lote, um único envio esgota franquia ou satura o nó; o exporter precisa de sinal claro para reduzir o batch.

**Independent Test**: Com token válido e taxa ok, enviar um lote > 5 MB e outro ≤ 5 MB (ambos abaixo do teto global); conferir recusa 413 vs aceite do tamanho, e que a reserva de saldo não foi chamada na recusa por tamanho.

**Acceptance Scenarios**:

1. **Given** teto de aplicação 5 MB e teto global G (5 MB < G), **When** o produto envia um lote de tamanho > 5 MB, **Then** a resposta recusa o lote inteiro com HTTP 413 / gRPC RESOURCE_EXHAUSTED, nada é publicado e a reserva de saldo (spec006) não é acionada.
2. **Given** o mesmo tenant dentro da taxa, **When** o produto envia um lote de tamanho ≤ 5 MB (e ≤ G), **Then** a checagem de tamanho passa e o fluxo segue para a reserva de saldo se aplicável.
3. **Given** um lote com tamanho ≤ 5 MB mas > G do servidor, **When** o envio ocorre, **Then** o teto global já existente recusa o lote (comportamento atual 413 / RESOURCE_EXHAUSTED).

---

### User Story 3 - Ordem de avaliação e coexistência com saldo (Priority: P2)

Antes de reservar saldo (spec006), o collector avalia **taxa e depois tamanho**, para não reservar crédito que seria estornado em seguida.

**Why this priority**: Evita churn de reserva/refund e custo desnecessário no Core.

**Independent Test**: Simular taxa esgotada e lote grande demais; verificar que a API de reserva não é chamada. Simular lote ok em taxa e tamanho com saldo insuficiente; verificar recusa da spec006 (429 + Retry-After 120).

**Acceptance Scenarios**:

1. **Given** taxa esgotada, **When** o envio ocorre, **Then** a recusa é de taxa e a reserva de saldo não é chamada (tamanho nem é necessário para a decisão de recusa).
2. **Given** taxa ok e lote acima de 5 MB, **When** o envio ocorre, **Then** a recusa é de tamanho e a reserva de saldo não é chamada.
3. **Given** lote ok em taxa e tamanho e saldo insuficiente (spec006), **When** o envio ocorre, **Then** a recusa é a de saldo insuficiente da LLT-186 (429 + Retry-After 120, não redefinida aqui).

---

### User Story 4 - Assinatura SUSPENSA e flag de excedente (Priority: P2)

Assinatura com status SUSPENSA bloqueia ingestão com 403 / PERMISSION_DENIED. Quando `overageEnabled=true` no plano/assinatura, a reserva pode autorizar além do saldo e registrar bytes de excedente separadamente (faturamento fora de escopo). Sem a flag, mantém-se o corte ao zerar da spec006.

**Why this priority**: ADR_163 exige bloqueio de SUSPENSA; planilha v4 prevê excedente só em planos pagos — flag por plano resolve o conflito Q11 sem implementar cobrança.

**Independent Test**: Assinatura SUSPENSA → 403 sem publicar. Assinatura ATIVA com `overageEnabled=false` e saldo zero → 429 saldo. Com `overageEnabled=true` e saldo insuficiente → autorização com registro de excedente (sem cobrança nesta feature).

**Acceptance Scenarios**:

1. **Given** assinatura SUSPENSA, **When** o produto envia lote OTLP autenticado (passando taxa/tamanho), **Then** a resposta é HTTP 403 / gRPC PERMISSION_DENIED e nada é publicado.
2. **Given** assinatura ATIVA com `overageEnabled=false` e saldo insuficiente, **When** reserva, **Then** mantém-se INSUFFICIENT_BALANCE / 429 da spec006.
3. **Given** assinatura ATIVA com `overageEnabled=true` e saldo insuficiente, **When** reserva, **Then** a ingestão é autorizada e os bytes excedentes são registrados separadamente no Core (cobrança fora de escopo).

---

### User Story 5 - Observabilidade da recusa por plano (Priority: P3)

Operação consegue distinguir quando taxa, tamanho, saldo, suspensão ou Core indisponível bloqueiam a ingestão.

**Why this priority**: Sem sinal, 429 de taxa e 429 de saldo parecem iguais ao operador.

**Independent Test**: Forçar cada classe de recusa e verificar métrica/log com motivo distinto.

**Acceptance Scenarios**:

1. **Given** recusas por tamanho, taxa, saldo, suspensão e Core fora, **When** a observabilidade está ativa, **Then** métricas e logs estruturados registram o motivo de forma distinguível (alerta in-app ao Dono/Admin fora desta entrega).

---

### Edge Cases

- Tenant sem plano atribuído: mesmo caminho 503 da spec006 (Retry-After **300 s**); **conta** no orçamento de 5 tentativas (D4).
- Assinatura SUSPENSA: 403 / PERMISSION_DENIED (D6).
- Assinatura INADIMPLENTE: **[NEEDS CLARIFICATION: Q14]**.
- Core indisponível na reserva: tentativas 1..5 → 503 / UNAVAILABLE + Retry-After **300 s**; tentativa 6+ → 500 / INTERNAL sem Retry-After (lote perdido). Redis do contador fora → 503/300 sem orçamento.
- Redis indisponível para rate limit: comportamento provisório configurável (recomendação: fail-open + WARN/métrica) **[A CONFIRMAR: Q13]**.
- Contadores de taxa com mais de uma instância do Collector: Redis compartilhado (D9) coordena o limite.
- Logs e traces: contador compartilhado (D2).
- Escopo: por tenant, soma de todos os produtos (D1).
- Excedente: só se `overageEnabled` (D7); cobrança fora de escopo.
- Upload manual (ADR_043/050) permanece fora do escopo.
- Envelope de capacidade DevOps **não** é limite por tenant.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST recusar lotes OTLP cujo tamanho exceda o menor entre **5 MB** (`lhamalog.otlp.max-batch-size`, default 5MB) e o teto técnico global (`lhamalog.otlp.max-message-size`, default 64MB), com HTTP 413 / gRPC RESOURCE_EXHAUSTED, lote inteiro, sem publicação e sem reserva.
- **FR-002**: O sistema MUST aplicar limite de taxa de **20 requisições OTLP/segundo por tenant** (janela fixa de 1 s; logs+traces; todos os produtos), via contadores no Redis.
- **FR-003**: O escopo da taxa e do tamanho de aplicação MUST ser por **tenant** (empresa).
- **FR-004**: Contagem de taxa de logs e traces MUST compartilhar o **mesmo** contador por tenant.
- **FR-005**: Lote acima do tamanho de aplicação MUST ser recusado **inteiro** (HTTP 413 / gRPC RESOURCE_EXHAUSTED), alinhado ao teto global.
- **FR-006**: Recusa por taxa MUST usar HTTP 429 e gRPC RESOURCE_EXHAUSTED com Retry-After = `lhamalog.rate-limit.retry-after` (default **5 s**). Após `violations-before-block` (default 5) violações em `violation-window` (default 60 s), o tenant MUST ser bloqueado por `block-duration` (default 300 s) com Retry-After = `block-retry-after` (default 300 s). Métricas/logs MUST usar `rate_limit` vs `rate_limit_blocked` e distinguir da 429 de saldo da spec006.
- **FR-007**: Quando o Core estiver indisponível (ou sem plano/ciclo — caminho 503 da spec006), o Collector MUST responder 503 / UNAVAILABLE com Retry-After `lhamalog.quota.unavailable-retry-after` default **300 s**, até `unavailable-max-attempts` (default **5**) por lote (chave = reservationId determinístico). Acima do orçamento MUST responder HTTP 500 / gRPC INTERNAL sem Retry-After (`quota_unavailable_exhausted`). Saldo insuficiente permanece Retry-After **120 s**.
- **FR-008**: Contadores de taxa MUST ser armazenados no **Redis** já usado no MVP (chaves com TTL curto; compartilhado entre instâncias).
- **FR-009**: Assinatura **SUSPENSA** MUST bloquear ingestão com HTTP 403 / gRPC PERMISSION_DENIED. Estado **INADIMPLENTE** permanece **[NEEDS CLARIFICATION: Q14]**. Defaults de novos campos no Core: status `ATIVA`, `overageEnabled=false` (compatível com documentos legados).
- **FR-010**: O sistema MUST emitir métricas e logs estruturados distinguindo recusa por tamanho, taxa, saldo, suspensão e Core indisponível. Alerta in-app ao Dono/Admin está fora desta entrega.
- **FR-011**: A ordem de avaliação MUST ser **taxa → tamanho → reserva de saldo (spec006)**.
- **FR-012**: Esta feature MUST NOT reimplementar reserva de saldo, refund ou SALDO_BAIXO da spec006/LLT-186 (apenas estende o contrato de reserva para SUSPENSA e `overageEnabled`).
- **FR-013**: Excedente além da franquia MUST ser permitido somente quando `overageEnabled=true` na assinatura/plano; bytes excedentes MUST ser registrados separadamente no Core. Cobrança/faturamento do excedente está fora de escopo. Sem a flag, mantém-se o corte ao zerar da spec006.
- **FR-014**: Respostas de recusa MUST ser distinguíveis entre tamanho (413), taxa (429 + `rate_limit`), bloqueio de taxa (429 + `rate_limit_blocked`), saldo (429 + Retry-After 120), suspensão (403), Core indisponível retentável (503) e orçamento Core esgotado (500/INTERNAL).
- **FR-015**: Os limites desta feature MUST aplicar-se aos canais OTLP HTTP e gRPC já existentes (logs e traces), **após** autenticação por token Bearer (tenant conhecido).
- **FR-016**: Redis indisponível no rate limiter MUST seguir política configurável provisória (recomendação: fail-open + métrica/WARN) marcada **[A CONFIRMAR: Q13]**; a escolha final aguarda Michel.
- **FR-017**: A medição do tamanho de aplicação (5 MB) MUST usar o tamanho do **payload decodificado/descomprimido** (alinhado ao teto global atual), aplicada **depois** da taxa e **antes** da reserva, em HTTP e gRPC.

### Key Entities

- **Limite de taxa do tenant**: teto de 20 req/s em janela fixa de 1 s, escopo tenant, contador Redis compartilhado logs+traces.
- **Limite de tamanho de lote de aplicação**: 5 MB por requisição OTLP; limitado superiormente pelo teto global (64 MB).
- **Contador de taxa**: chave Redis por tenant e segundo epoch; decide aceite ou 429.
- **PlanSubscription (Core)**: passa a incluir `status` (ATIVA / SUSPENSA / INADIMPLENTE) e `overageEnabled` (boolean).
- **Decisão de ingestão**: resultado após autenticação — taxa, tamanho, (depois) saldo/suspensão/excedente, destino/fila — com classe de erro distinta por motivo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos casos de teste em que o lote excede 5 MB (abaixo do teto global), o envio é recusado com 413/RESOURCE_EXHAUSTED e nenhum registro do lote é aceito/publicado.
- **SC-002**: Em 100% dos casos em que a taxa do tenant está esgotada na janela de 1 s (≥ 20 req/s), o envio é recusado com 429 / RESOURCE_EXHAUSTED e Retry-After da propriedade de taxa, e o lote não é publicado.
- **SC-003**: Em cenários combinados com spec006, lotes recusados por tamanho ou taxa não disparam reserva de saldo (0 chamadas de reserva nesses caminhos).
- **SC-004**: Um operador consegue distinguir, em métricas/logs, recusa por tamanho vs taxa vs saldo vs suspensão vs Core fora.
- **SC-005**: Exporter OTLP padrão, ao receber a recusa de taxa com Retry-After, consegue reagendar envio sem intervenção manual (verificável em teste de integração).
- **SC-006**: Assinatura SUSPENSA resulta em 403 / PERMISSION_DENIED em 100% dos casos de teste, sem publicação.
- **SC-007**: Com `overageEnabled=true` e saldo insuficiente, a reserva autoriza e registra excedente; com `false`, mantém corte da spec006.

## Assumptions

- O teto técnico global atual (`OTLP_RECEIVER_MAX_MESSAGE_SIZE`, padrão 64MB) permanece como limite superior absoluto do servidor.
- Autenticação por token Bearer e resolução produto→tenant já existentes são pré-requisito; limites desta feature aplicam-se depois da autenticação bem-sucedida.
- LLT-186/spec006 cobre saldo/franquia; esta spec estende apenas status/excedente no contrato de reserva e o Retry-After do caminho Core/sem plano.
- Upload manual e canais não-OTLP estão fora de escopo.
- Valores 20 req/s e 5 MB foram decididos pelo Michel (16/11/2026); defaults configuráveis no Collector até existirem na TabelaPrecificacao.
- Documentação em pt-BR; sem segredos; sem alterações em Jira/ADR/código de produção neste passo SpecKit.

## Out of Scope

- Implementar cobrança/faturamento do excedente pago por GB.
- Reimplementar reserva de bytes do ciclo, refund e SALDO_BAIXO (exceto extensões SUSPENSA/`overageEnabled` e Retry-After 300 s no caminho Core fora).
- Limites do upload manual (ADR_043/050).
- Usar o envelope DevOps Capacity v1 como cota por tenant.
- Alerta in-app ao Dono/Admin por taxa/tamanho.
- Preencher TabelaPrecificacao/`rateLimit` no Core com valores por plano comercial distintos (MVP usa config do Collector).

## Dependencies

- Collector OTLP HTTP/gRPC autenticado (specs 001/002/004).
- Redis do compose/MVP (ADR_164) para contadores de taxa e Streams.
- spec006/LLT-186 para reserva de saldo e ordem pós-tamanho.
- Core `PlanSubscription` + `QuotaReservationService` / `UsageReservationsController` para status e `overageEnabled`.

## Perguntas ainda abertas

| Id | Status | Pergunta |
|----|--------|----------|
| Q13 | **[A CONFIRMAR]** | Redis down no rate limiter — fail-open (recomendado) vs fail-closed |
| Q14 | **[NEEDS CLARIFICATION]** | Comportamento de ingestão para assinatura INADIMPLENTE |
