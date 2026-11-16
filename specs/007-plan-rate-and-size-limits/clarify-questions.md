# Clarify — LLT-183 / 007-plan-rate-and-size-limits

Sessão aberta em **11/11/2026**. Respostas do Michel em **16/11/2026 via BimBot Escola de TI** (D1–D9). Itens ainda abertos estão marcados **Aberta** ou **Parcial**.

Legenda de status: **Respondida** | **Parcial** | **Aberta**.

---

## Q1 — Valores por plano e fonte de verdade — **Respondida** (parcial na fonte)

**Pergunta:** De onde vêm os valores numéricos de taxa e de tamanho máximo de lote por plano, e qual é a fonte de verdade em runtime?

**Decisão (Michel, 16/11/2026):** taxa = **20 req/s**; tamanho = **5 MB**.

**Fonte de runtime (decisão técnica do dev):** propriedades do Collector (`lhamalog.rate-limit.*`, `lhamalog.otlp.max-batch-size`) no MVP — TabelaPrecificacao/`rateLimit` no Core permanece vazia (ADR_131); não inventar preenchimento.

**Opções originais (A/B/C):** superadas pelos números fixos do Michel; mapeamento mais próximo de B (config no Collector) até o Core ter a tabela.

---

## Q2 — Unidade da taxa e algoritmo/janela — **Respondida**

**Pergunta:** Qual unidade mede a taxa e qual algoritmo/janela a aplica?

**Decisão (Michel, 16/11/2026 / D1):** requisições por segundo.

**Algoritmo (decisão técnica do dev):** janela fixa de **1 segundo** por tenant no Redis (INCR atômico + EXPIRE; chave `lhamalog:ratelimit:{tenantId}:{epochSecond}`; TTL 2 s), contagem por requisição OTLP (HTTP e gRPC, logs e traces) após autenticação.

---

## Q3 — Escopo: tenant vs produto — **Respondida**

**Decisão (Michel, 16/11/2026 / D1):** por **tenant** (empresa), somando todos os produtos. Opção A.

---

## Q4 — Logs e traces juntos ou separados — **Respondida**

**Decisão (Michel, 16/11/2026 / D2):** cota **única** (logs + traces). Opção A.

---

## Q5 — Lote acima do tamanho: recusa inteira vs parcial (+ gRPC) — **Respondida**

**Decisão (Michel, 16/11/2026 / D3):** lote máximo **5 MB**; recusa do **lote inteiro** com HTTP **413** (gRPC **RESOURCE_EXHAUSTED**). Opção A.

---

## Q6 — Core fora: cache e fail open vs fail closed — **Respondida**

**Decisão (Michel, 16/11/2026 / D4, sessão 2):** recusar com Retry-After de **5 min (300 s)**, até **5 tentativas por lote** (25 min); depois o lote é **perdido**.

**Decisão técnica do dev:** identidade = `reservationId` determinístico LLT-186; contador Redis `lhamalog:quota:core-unavailable:{batchKey}`; tentativas 1..5 → 503/300; 6+ → 500/INTERNAL sem Retry-After; NO_PLAN conta (mesmo 503); Redis down no contador → 503/300 sem orçamento.

**Nota de escopo:** caminho Core/sem plano da spec006; saldo insuficiente permanece Retry-After 120.

---

## Q7 — Armazenamento dos contadores de taxa — **Respondida**

**Decisão (Michel, 16/11/2026 / D9):** contadores no **Redis**. Opção A (Redis do compose/MVP).

---

## Q8 — Valor do Retry-After para taxa — **Respondida**

**Decisão (Michel, 16/11/2026 / D5, sessão 2):** acima de 20 req/s → Retry-After **5 s**; **5 violações em 1 min** → bloqueio **5 min** com Retry-After **300 s**. A 5ª violação já responde com 300; reqs no bloqueio não contam na janela de 1 s.

**Propriedades:** `retry-after=5s`, `violations-before-block=5`, `violation-window=60s`, `block-duration=300s`, `block-retry-after=300s`.

---

## Q9 — Aplicação a sem plano / FREE / ilimitado / SUSPENSA / INADIMPLENTE — **Parcial**

**Decisão (Michel, 16/11/2026 / D6):** assinatura **SUSPENSA** → HTTP **403** (gRPC **PERMISSION_DENIED**).

**Ainda aberto:** **INADIMPLENTE** → ver **Q14**. Sem plano / FREE / ilimitado: sem plano segue spec006 (503); FREE e pagos usam os mesmos 20 req/s e 5 MB no MVP (config Collector); `unlimitedUsage` da spec006 não isenta taxa/tamanho.

---

## Q10 — Observabilidade e notificação — **Respondida** (decisão técnica do dev)

Michel não decidiu. **Decisão técnica do dev (MVP):** métricas + log estruturado (opção C / A sem alerta). Alerta ao Dono/Admin fora desta entrega.

---

## Q11 — Conflito: excedente pago (v4) vs corte ao zerar saldo (spec006) — **Respondida**

**Decisão (Michel, 16/11/2026 / D7):** excedente permitido e cobrado à parte **somente** se habilitado por plano (`overageEnabled`); senão mantém o corte ao zerar da spec006. Cobrança em si fora de escopo. Próximo de opção B com flag explícita.

---

## Q12 — Ordem de avaliação — **Respondida**

**Decisão (Michel, 16/11/2026 / D8):** **taxa → tamanho → reserva de saldo**. Opção B (sobrescreve a recomendação técnica anterior A).

---

## Q13 — Redis indisponível para o rate limiter — **Aberta** [A CONFIRMAR]

**Pergunta (nova, 16/11/2026):** Se o Redis do rate limit não responder, o Collector falha aberto (aceita a requisição quanto à taxa, com métrica/WARN) ou falha fechado (recusa)?

**Opções:**

| Opção | Descrição |
|-------|-----------|
| A | Fail-open + métrica/WARN (provisório configurável) |
| B | Fail-closed → 503/UNAVAILABLE com Retry-After |
| C | Fail-open só se Streams também estiverem saudáveis; senão fail-closed |

**Recomendação técnica do dev:** **A** — publicação em Redis Streams falharia de qualquer modo se o Redis estiver fora; fail-open evita 429 falso e deixa a falha aparecer no sink/stream.

**Impacto se não decidido:** implementação usa A como default provisório marcado [A CONFIRMAR].

---

## Q14 — Assinatura INADIMPLENTE — **Aberta** [NEEDS CLARIFICATION]

**Pergunta (nova, 16/11/2026):** Com assinatura INADIMPLENTE, a ingestão bloqueia como SUSPENSA (403), permanece ativa durante graça de 15 dias (ADR_163), ou outro comportamento?

**Opções:**

| Opção | Descrição |
|-------|-----------|
| A | Igual SUSPENSA → 403 / PERMISSION_DENIED |
| B | Graça de 15 dias (ADR_163) com ingestão permitida (sujeita a taxa/tamanho/saldo); depois bloqueio |
| C | Ingestão permitida sem graça explícita até transição manual para SUSPENSA |

**Recomendação técnica do dev:** **B** alinhada ao ADR_163, mas **não adotar sem Michel** — campo `status=INADIMPLENTE` pode existir no modelo com comportamento provisório documentado ou tratado como ATIVA até confirmação (explicitar na implementação).

**Impacto se não decidido:** modelo Core inclui o enum; comportamento de ingestão para INADIMPLENTE fica [NEEDS CLARIFICATION] / tarefa bloqueada ou trata como ATIVA provisoriamente com flag.
