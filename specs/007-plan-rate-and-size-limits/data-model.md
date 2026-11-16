# Modelo de Dados — 007-plan-rate-and-size-limits

## Contador de taxa (Redis)

| Campo / chave | Tipo | Regras |
|---------------|------|--------|
| Janela | `lhamalog:ratelimit:{tenantId}:{epochSecond}` | contagem naquele segundo; TTL **2 s** |
| Violações | `lhamalog:ratelimit:violations:{tenantId}` | INCR em cada req acima do limite; TTL = `violation-window` (default 60 s) |
| Bloqueio | `lhamalog:ratelimit:block:{tenantId}` | SET com TTL = `block-duration` (default 300 s) quando violações ≥ limiar |
| Limite | 20 | `lhamalog.rate-limit.requests-per-second` |
| Escopo | tenant | logs + traces + todos os produtos somam |

**Transição**: Lua atômica — se bloqueado → `BLOCKED` (não INCR janela); senão INCR janela; se > limite → INCR violações; se violações ≥ 5 → SET block e `BLOCKED` (a 5ª já responde com Retry-After de bloqueio); senão `LIMITED`.

## Orçamento Core indisponível (Redis)

| Campo / chave | Tipo | Regras |
|---------------|------|--------|
| Chave | `lhamalog:quota:core-unavailable:{batchKey}` | `batchKey` = reservationId determinístico (`quota:` + SHA-256…) |
| Valor | inteiro | tentativas de recusa Core-unavailable / NO_PLAN / timeout / 5xx |
| TTL | `unavailable-attempt-ttl` default **30 m** | EXPIRE no primeiro INCR (Lua) |
| Limiar | `unavailable-max-attempts` default **5** | 1..5 → 503; 6+ → drop |
| Limpeza | DELETE | no sucesso (confirm Core ok) |

## Limites de tamanho (config)

| Propriedade | Default | Medição |
|-------------|---------|---------|
| `lhamalog.otlp.max-message-size` | 64MB | payload descomprimido (existente, teto de transporte) |
| `lhamalog.otlp.max-batch-size` | 5MB | payload descomprimido/decodificado (novo, aplicação) |

Efetivo = min(max-batch-size, max-message-size).

## PlanSubscription (Core) — extensão

Documento Mongo `PlanSubscription` (existente) + campos:

| Campo | Tipo | Default legado | Validação |
|-------|------|----------------|-----------|
| `status` | enum `ATIVA` \| `SUSPENSA` \| `INADIMPLENTE` | `ATIVA` | obrigatório após migrate lógica |
| `overageEnabled` | boolean | `false` | |
| (existentes) | `tenantId`, `productId`, `planType`, `firstPaymentDate`, `allowanceGigabytes`, `unlimitedUsage` | — | inalterados |

### Transições de status (negócio)

| De | Para | Efeito na ingestão |
|----|------|--------------------|
| ATIVA | SUSPENSA | reserve → SUSPENDED → OTLP 403 |
| SUSPENSA | ATIVA | volta ao fluxo normal |
| * | INADIMPLENTE | **[NEEDS CLARIFICATION Q14]** — até decisão, tratar como ATIVA na reserva |

A transição em si (quem muda o status) está fora desta feature (Asaas/ADR_163); aqui só o efeito na reserva/OTLP.

## Saldo / excedente (Core)

Quando `overageEnabled=true` e a reserva excederia o saldo:

- Autorizar a reserva (outcome AUTHORIZED ou novo `AUTHORIZED_OVERAGE` — preferir outcome explícito se facilitar métricas).
- Registrar bytes além do saldo em acumulador de excedente do ciclo (ex.: `overageBytes` no documento de saldo/ciclo).
- Não emitir cobrança.

Quando `overageEnabled=false`: comportamento spec006 (INSUFFICIENT_BALANCE / 409).

## Decisões de ingestão (Collector)

| Outcome interno | HTTP | gRPC | Retry-After |
|-----------------|------|------|-------------|
| RateLimited (janela) | 429 | RESOURCE_EXHAUSTED | `retry-after` default **5** |
| RateLimited (bloqueio) | 429 | RESOURCE_EXHAUSTED | `block-retry-after` default **300** |
| BatchTooLarge | 413 | RESOURCE_EXHAUSTED | (sem Retry-After obrigatório) |
| QuotaExceeded (saldo) | 429 | RESOURCE_EXHAUSTED | 120 |
| Suspended | 403 | PERMISSION_DENIED | — |
| QuotaUnavailable | 503 | UNAVAILABLE | 300 |
| QuotaUnavailableExhausted | 500 | INTERNAL | — |

## Relacionamentos

```text
Token → Product → TenantId
                 ├─ RateCounter / Violations / Block (Redis)
                 ├─ CoreUnavailableAttempts (Redis, por reservationId)
                 └─ PlanSubscription (Core)
                        ├─ status / overageEnabled
                        └─ QuotaBalance / Reservation (spec006 + overageBytes)
```
