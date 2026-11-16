# Quickstart de validação — 007-plan-rate-and-size-limits

Guia para provar a feature após implementação. Sem código de produção neste documento.

## Pré-requisitos

- Collector e Core buildáveis (`.\mvnw.cmd` em cada worktree).
- Redis acessível (compose local do projeto).
- Token de produto válido e `PlanSubscription` de teste no Core.
- Contratos: [otlp-refusal-mapping.md](contracts/otlp-refusal-mapping.md), [usage-reservations-extension.md](contracts/usage-reservations-extension.md).

## Propriedades relevantes (nomes)

| Propriedade | Default esperado |
|-------------|------------------|
| `lhamalog.rate-limit.requests-per-second` | 20 |
| `lhamalog.rate-limit.retry-after` | **5 s** (D5) |
| `lhamalog.rate-limit.violations-before-block` | 5 |
| `lhamalog.rate-limit.violation-window` | 60 s |
| `lhamalog.rate-limit.block-duration` | 300 s |
| `lhamalog.rate-limit.block-retry-after` | 300 s |
| `lhamalog.otlp.max-batch-size` | 5MB |
| `lhamalog.otlp.max-message-size` | 64MB |
| `lhamalog.quota.unavailable-retry-after` | 300 s (Core/sem plano) |
| `lhamalog.quota.unavailable-max-attempts` | 5 |
| `lhamalog.quota.unavailable-attempt-ttl` | 30 m |
| Retry-After saldo insuficiente | 120 s (spec006, inalterado) |

## Cenários

### 1. Taxa dentro do limite

1. Autenticar e enviar ≤ 20 requisições OTLP/s (mistura logs/traces ok).
2. **Esperado**: aceites conforme saldo; nenhuma 429 de taxa.

### 2. Taxa excedida

1. Enviar > 20 requisições no mesmo segundo para o mesmo tenant.
2. **Esperado**: 429 / RESOURCE_EXHAUSTED + Retry-After **5**; **0** chamadas a `POST /internal/usage-reservations`; métrica/log `rate_limit`.

### 2b. Bloqueio por violações repetidas

1. Forçar 5 violações de taxa em ≤ 60 s.
2. **Esperado**: a 5ª (e as seguintes no bloqueio) → 429 + Retry-After **300**; `reason=rate_limit_blocked`.

### 3. Tamanho > 5 MB

1. Com taxa ok, enviar lote descomprimido > 5 MB e < 64 MB.
2. **Esperado**: 413 / RESOURCE_EXHAUSTED; sem reserva; log `batch_too_large`.

### 4. Ordem taxa → tamanho → reserva

1. Esgotar taxa e enviar também lote grande: recusa de **taxa** (não precisa chegar a tamanho).
2. Taxa ok + lote grande: recusa de **tamanho**, sem reserva.
3. Taxa ok + tamanho ok + saldo zero (`overageEnabled=false`): 429 + Retry-After **120**.

### 5. SUSPENSA

1. `PlanSubscription.status=SUSPENSA`.
2. Enviar lote válido (taxa/tamanho ok).
3. **Esperado**: Core 403 `SUSPENDED` → OTLP 403 / PERMISSION_DENIED.

### 6. Overage

1. `overageEnabled=true`, saldo insuficiente.
2. **Esperado**: autorização com registro de excedente; lote pode seguir (sem cobrança).
3. Repetir com `overageEnabled=false` → 429 saldo.

### 7. Core indisponível (orçamento D4)

1. Derrubar Core ou forçar timeout no cliente; reenviar o **mesmo** lote até 5 vezes.
2. **Esperado**: cada tentativa 1..5 → 503 / UNAVAILABLE + Retry-After **300**.
3. 6ª tentativa com Core ainda fora → **500** / INTERNAL sem Retry-After; `quota_unavailable_exhausted`.
4. Com Core de volta antes de esgotar → aceite e chave de tentativas apagada.

### 8. Suíte automatizada

```text
# Collector worktree (alvos sugeridos)
.\mvnw.cmd -q test "-Dtest=RateLimitAndBatchSizePropertiesTest,RedisRateLimiterTest,RedisCoreUnavailableAttemptTrackerTest,BatchIngestionCoreUnavailableBudgetTest,BatchIngestionRateAndSizeTest,BatchIngestionQuotaTest,HttpQuotaReservationClientTest,CanonicalQuotaGateTest,OtlpHttpRateAndSizeTest,OtlpGrpcRateAndSizeTest,OtlpHttpSuspendedTest,OtlpGrpcSuspendedTest,OtlpHttpQuotaStatusMapperTest"
```

Registrar evidência em `progress-notes.md` ou arquivo e2e desta pasta quando a implementação existir.

## Itens [A CONFIRMAR] na validação

- Q13: simular Redis down no rate limiter e conferir fail-open provisório.
- Q14: INADIMPLENTE — não afirmar bloqueio até Michel decidir.
