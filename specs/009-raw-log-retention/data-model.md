# Data Model: 009-raw-log-retention

## TenantStreamName

| Campo | Tipo | Regra |
|-------|------|--------|
| signalPrefix | string | `logs` ou `traces` (config `OPENOBSERVE_*_STREAM`) |
| tenantId | string | Id do token; nunca logar além das convenções LLT-183 |
| sanitized | string | lower(tenantId); `[^a-z0-9]` → `_`; max 40 chars |
| hashSuffix | string? | `_` + 8 hex SHA-256(tenantId) se sanitização ≠ lower original sem mudança, ou se truncou |
| streamName | string | `{prefix}_{sanitized}`[hashSuffix] |

**Invariantes**: só `[a-z0-9_]`; determinístico; colisão-seguro entre ids que sanitizam igual.

## StreamRetentionCacheEntry

| Campo | Tipo | Regra |
|-------|------|--------|
| streamType | enum | `logs` \| `traces` |
| streamName | string | nome efetivo |
| retentionDays | int | N > 0 aplicado com sucesso |
| cachedAt | instant | para TTL (1 h) |
| failureUntil | instant? | backoff negativo após falha (~60 s) |

## ReservationWireResult

| Campo | Tipo | Regra |
|-------|------|--------|
| outcome | enum | AUTHORIZED, AUTHORIZED_UNLIMITED, … |
| retentionDays | Integer? | presente só se outcome autoriza; null/ausente/≤0 = unknown |
| reservationId | string? | existente |
| overageBytes | long? | existente (ignorado para retenção) |

## StoredBatch (existente, valor muda)

| Campo | Mudança |
|-------|---------|
| stream | passa a ser o stream do tenant |
| organization | inalterado |
| demais | inalterados |

## TelemetryIngestedBody.OpenObserveRef (existente)

| Campo | Mudança |
|-------|---------|
| stream | valor = stream do tenant |
| schema | sem novo campo; sem bump de versão do envelope |

## PlanTier retention (referência ADR_131 / Core)

| Plano | retencaoDias |
|-------|--------------|
| FREE | 7 |
| STARTER | 15 |
| PRO | 30 |
| BUSINESS | 90 |
| ENTERPRISE | 90 |

Tenant multi-produto: **max** entre assinaturas com plano (recomendação).
