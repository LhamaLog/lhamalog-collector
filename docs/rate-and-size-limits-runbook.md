# Runbook: limites de taxa e tamanho por tenant (LLT-183 / spec 007)

Procedimentos operacionais da feature de rate limit, tamanho de lote e caminhos
503/403 da LLT-183. Fontes: `specs/007-plan-rate-and-size-limits/spec.md`,
`contracts/otlp-refusal-mapping.md`, `quickstart.md`. Não registrar tokens, corpos
OTLP nem senhas Redis em tickets ou logs compartilhados.

Ordem de checagem após autenticação: **taxa → tamanho → reserva/saldo**.

## Rate limit 429 + Retry-After 5 (`reason=rate_limit`)

**Sintomas:** HTTP `429` / gRPC `RESOURCE_EXHAUSTED` com `Retry-After: 5`; métrica
`lhamalog.ingestion.refused{reason=rate_limit}`; sem chamada de reserva ao Core;
lote não publicado.

**Causa:** o tenant ultrapassou `lhamalog.rate-limit.requests-per-second` (default 20)
na janela fixa de 1 s (logs + traces, todos os produtos).

**Ação:**

1. Oriente o exportador a respeitar o Retry-After de 5 s; não force rajadas.
2. Confirme se o volume do tenant é esperado (produtos, retries, exporters duplicados).
3. Se precisar afrouxar no MVP, ajuste as propriedades do Collector (não invente
   `rateLimit` na TabelaPrecificacao enquanto estiver vazia).

**Propriedades:** `lhamalog.rate-limit.enabled` (true), `requests-per-second` (20),
`retry-after` (5s), `on-store-unavailable` (FAIL_OPEN).

**Chaves Redis (prefixos):** janela `lhamalog:ratelimit:{tenantId}:{epochSecond}`;
violações `lhamalog:ratelimit:violations:{tenantId}`; bloqueio
`lhamalog:ratelimit:block:{tenantId}` (ver `RedisRateLimiter`).

## Bloqueio de taxa 429 + Retry-After 300 (`reason=rate_limit_blocked`)

**Sintomas:** HTTP `429` / gRPC `RESOURCE_EXHAUSTED` com `Retry-After: 300`; métrica
`lhamalog.ingestion.refused{reason=rate_limit_blocked}`. A 5ª violação na janela de
60 s já responde com 300 s; reqs no bloqueio não incrementam a janela de 1 s.

**Causa:** `violations-before-block` (default 5) violações em `violation-window`
(default 60 s) → bloqueio por `block-duration` (default 300 s).

**Ação:**

1. Espere o TTL do bloqueio ou limpe a chave de bloqueio do tenant (sem colar senha):

```bash
# inspecionar
redis-cli -a "$REDIS_PASSWORD" EXISTS "lhamalog:ratelimit:block:<tenantId>"
redis-cli -a "$REDIS_PASSWORD" TTL "lhamalog:ratelimit:block:<tenantId>"
# limpar bloqueio (só se operação autorizar)
redis-cli -a "$REDIS_PASSWORD" DEL "lhamalog:ratelimit:block:<tenantId>"
# opcional: zerar contador de violações da janela
redis-cli -a "$REDIS_PASSWORD" DEL "lhamalog:ratelimit:violations:<tenantId>"
```

2. Investigue a causa das violações repetidas antes de limpar em produção.

**Propriedades:** `lhamalog.rate-limit.violations-before-block` (5),
`violation-window` (60s), `block-duration` (300s), `block-retry-after` (300s).

## Lote > 5 MB → 413 / RESOURCE_EXHAUSTED (`reason=batch_too_large`)

**Sintomas:** HTTP `413` / gRPC `RESOURCE_EXHAUSTED`; métrica
`lhamalog.ingestion.refused{reason=batch_too_large}`; sem reserva; sem publicação.
Medição no payload **decodificado/descomprimido**. Teto efetivo =
`min(max-batch-size, max-message-size)`.

**Causa:** lote acima de `lhamalog.otlp.max-batch-size` (default 5MB), ainda abaixo
do teto técnico global (default 64MB). Acima do teto global continua o caminho
TOO_LARGE existente.

**Ação:** oriente o exportador a reduzir o tamanho do batch OTLP.

**Propriedades:** `lhamalog.otlp.max-batch-size` (5MB),
`lhamalog.otlp.max-message-size` (64MB).

## Assinatura SUSPENSA → 403 / PERMISSION_DENIED (`reason=subscription_suspended`)

**Sintomas:** HTTP `403` / gRPC `PERMISSION_DENIED`; métrica
`lhamalog.ingestion.refused{reason=subscription_suspended}`; nada publicado.

**Causa:** `PlanSubscription.status=SUSPENSA` no Core (após taxa/tamanho ok).

**Ação:** no Core, regularize a assinatura do tenant; não force reenvio no Collector.

**Propriedades:** status da assinatura no Core (default de documentos novos: `ATIVA`).

## Assinatura INADIMPLENTE (carência 15 dias)

**Sintomas durante a carência:** ingestão permitida (seguindo taxa/tamanho/saldo).
**Após a carência:** igual a SUSPENSA → 403 / PERMISSION_DENIED.
**Sem `delinquentSince`:** ingestão permitida com WARN
`event=subscription.delinquent.missing_since` (no Core).

**Causa:** carência = `delinquentSince + lhamalog.subscription.delinquency-grace`
(default **15d**, propriedade do **Core**).

**Ação:** confira `delinquentSince` e o fim da carência no Core; após o prazo trate
como suspensão.

**Propriedades (Core):** `lhamalog.subscription.delinquency-grace` (15d).

## Core indisponível / sem plano → 503 + Retry-After 300 (até 5 tentativas)

**Sintomas (tentativas 1..5):** HTTP `503` / gRPC `UNAVAILABLE` com `Retry-After: 300`;
métrica `lhamalog.ingestion.refused{reason=quota_unavailable}`; log
`event=quota.core_unavailable.retry`. `NO_PLAN` / sem plano usa o **mesmo** caminho.

**Sintomas (tentativa 6+):** HTTP `500` / gRPC `INTERNAL` **sem** Retry-After; métrica
`reason=quota_unavailable_exhausted`; log `event=quota.core_unavailable.exhausted`;
lote perdido (não retentável OTLP).

**Causa:** Core fora, timeout, 5xx ou ausência de plano/ciclo; orçamento por lote
(`reservationId` determinístico) em Redis.

**Ação:**

1. Verifique saúde do Core e `lhamalog.usage.core.url` / token de serviço (sem colar o token).
2. Inspecione/limpe chaves de tentativa (prefixo `lhamalog:quota:core-unavailable:`):

```bash
redis-cli -a "$REDIS_PASSWORD" KEYS "lhamalog:quota:core-unavailable:*"
redis-cli -a "$REDIS_PASSWORD" GET "lhamalog:quota:core-unavailable:<batchKey>"
redis-cli -a "$REDIS_PASSWORD" TTL "lhamalog:quota:core-unavailable:<batchKey>"
# limpar orçamento de um lote específico (só se operação autorizar)
redis-cli -a "$REDIS_PASSWORD" DEL "lhamalog:quota:core-unavailable:<batchKey>"
```

3. Com Core de volta, o fluxo processa e o Collector apaga a chave no sucesso.
4. Redis do contador indisponível → 503 + Retry-After 300 **sem** orçamento + WARN/métrica
   `quota_unavailable_attempt_store_unavailable`.

**Propriedades:** `lhamalog.quota.unavailable-retry-after` (300s),
`unavailable-max-attempts` (5), `unavailable-attempt-ttl` (30m).
Saldo insuficiente permanece Retry-After **120 s** (spec006) — ver
[quota-reservation-runbook.md](quota-reservation-runbook.md).

## Redis down no rate limiter (fail-open)

**Sintomas:** ingestão **não** é recusada por taxa; WARN
`event=ingestion.rate_limit.store_unavailable`; métrica
`lhamalog.ingestion.refused{reason=rate_limit_store_unavailable}`; o fluxo segue para
tamanho → reserva.

**Causa:** falha ao consultar Redis no `RedisRateLimiter` com política `FAIL_OPEN`.

**Ação:** restaure o Redis do Collector; monitore o WARN. Não trate como 429 de taxa.

**Propriedades:** `lhamalog.rate-limit.on-store-unavailable` (FAIL_OPEN).

## Métrica `lhamalog.ingestion.refused{reason}`

| `reason` | Quando |
|---|---|
| `rate_limit` | taxa excedida (Retry-After 5) |
| `rate_limit_blocked` | bloqueio de violações (Retry-After 300) |
| `batch_too_large` | lote > max-batch-size |
| `insufficient_balance` | saldo insuficiente (Retry-After 120) |
| `subscription_suspended` | assinatura SUSPENSA / carência INADIMPLENTE expirada |
| `quota_unavailable` | Core/sem plano retentável (503 / 300) |
| `quota_unavailable_exhausted` | orçamento 5 tentativas esgotado (500) |
| `rate_limit_store_unavailable` | Redis rate-limit down (fail-open) |
| `quota_unavailable_attempt_store_unavailable` | Redis do contador Core-unavailable down |

## Correlation id

- Header HTTP de entrada/saída: `X-Correlation-Id`
- Metadata gRPC: `x-correlation-id`
- MDC: `correlationId` (na linha de log aparece como `cid=...`)
- Aceito só se bater `^[A-Za-z0-9._-]{1,64}$`; senão o Collector gera UUID
- Workers/agendadores geram um cid novo por item processado

**Como grepar um pedido** (mesmo cid no Collector e no Core):

```bash
# Collector
rg "cid=<valor>" /var/log/lhamalog-collector/
# Core
rg "cid=<valor>" /var/log/lhamalog-core/
# ou pelo event code + tenant/product
rg "event=ingestion.refused|event=quota.core_unavailable" /var/log/lhamalog-collector/
```

## Códigos `event=` (LLT-183 e acompanhamento no Collector)

Códigos estáveis usados em WARN/ERROR/INFO de negócio (grep `event=` em `src/main/java`):

| Código | Nível típico | Contexto |
|---|---|---|
| `ingestion.refused` | INFO | recusa OTLP com `reason=` |
| `ingestion.rate_limit.store_unavailable` | WARN | Redis rate-limit indisponível |
| `ingestion.destination.not_confirmed` | WARN | destino não confirmou entrega |
| `ingestion.destination.refused` | WARN | destino recusou como dado inválido |
| `ingestion.destination.failed` | WARN | falha inesperada na entrega |
| `quota.core_unavailable.retry` | WARN | Core fora, tentativa no orçamento |
| `quota.core_unavailable.exhausted` | WARN | orçamento esgotado, lote dropado |
| `quota.core_unavailable.attempt_store_error` | WARN | contador de tentativas com erro |
| `quota.core_unavailable.attempt_store_unavailable` | WARN | Redis do contador fora |
| `quota.core_unavailable.attempt_clear_error` | WARN | falha ao limpar chave de tentativa |
| `quota.reservation.release_failed` | WARN | falha ao release após erro |
| `quota.refund.enqueue_failed` | ERROR | falha ao enfileirar refund |
| `quota.refund.deferred` | WARN | refund worker adiou item |
| `otlp.http.started` / `otlp.http.stopped` | INFO | ciclo de vida do receptor HTTP |
| `otlp.http.unexpected_failure` | ERROR | falha inesperada no handler HTTP |
| `otlp.grpc.started` / `otlp.grpc.stopped` | INFO | ciclo de vida do receptor gRPC |
| `auth.token_catalog.unavailable` | WARN | catálogo Mongo de tokens fora |
| `messaging.publish.retried` | INFO | publish no stream após retry |
| `messaging.publish.attempt_failed` | WARN | tentativa de publish falhou |
| `messaging.publish.moved_to_dlq` | ERROR | mensagem movida para DLQ |
| `messaging.publish.dlq_failed` | ERROR | falha ao gravar na DLQ |
| `usage.core.token_configured` | INFO | token de serviço (só length/sha256) |
| `usage.core.client_ready` | INFO | endpoint do cliente de usage-facts |
| `usage.outbox.worker_scheduled` | INFO | worker agendado |
| `usage.outbox.lease_unavailable` | WARN | item não disponível para lease |
| `usage.outbox.lease_cas_mismatch` | WARN | CAS de lease não bateu |
| `usage.outbox.delivered` | INFO | fato entregue ao Core |
| `usage.outbox.delivery_unexpected` | WARN | falha inesperada na entrega |
| `usage.outbox.deferred` | WARN | entrega adiada (retry) |
| `usage.outbox.suspended` | WARN | entrega suspensa (4xx) |
| `usage.outbox.pending_over_grace` | WARN | pending acima da grace |
| `usage.outbox.ambiguous` | ERROR | evidência Redis ambígua |
| `usage.outbox.unknown_evidence` | WARN | estado de evidência desconhecido |
| `usage.redis.aof_disabled` | WARN | AOF desligado com usage ligado |

## Referências

- [quota-reservation-runbook.md](quota-reservation-runbook.md) — saldo, refund, SALDO_BAIXO
- Contratos: `specs/007-plan-rate-and-size-limits/contracts/otlp-refusal-mapping.md`
- Quickstart: `specs/007-plan-rate-and-size-limits/quickstart.md`
