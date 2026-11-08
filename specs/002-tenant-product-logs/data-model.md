# Data Model: Associar logs ao tenant e ao produto (LLT-182)

**Responsável**: michelbocchijr@gmail.com

## Documento no OpenObserve

Como em `specs/001-otlp-receiver/data-model.md` (logs e traces), mais dois campos (2026-11-02):

| Campo | Valor |
|---|---|
| `lhamalog_batch_id` | UUID v3 do lote (igual em todos os documentos do lote) |
| `lhamalog_record_id` | UUID v3 do registro (research D5) |

## Entrada dos streams `lhamalog:stream:logs` e `lhamalog:stream:traces`

Uma entrada por lote aceito. Campos (todos texto):

| Campo | Valor |
|---|---|
| `idempotencyId` | `batchId` (research D9) |
| `event` | `log.ingested` ou `trace.ingested` |
| `origin` | `collector` |
| `schemaVersion` | `1` (logs e traces) |
| `publishedAt` | instante ISO-8601 UTC, ex. `2026-11-01T15:00:00Z` |
| `metadata` | JSON, ver abaixo |
| `body` | JSON, ver abaixo |

### metadata

| Chave | Obrigatória | Origem |
|---|---|---|
| `tenantId` | sim | token; só aparece aqui |
| `productId` | sim | token |
| `correlationId` | sim | UUID por chamada de exportação |
| `traceId` | quando o lote tem um só trace | hex de 32 caracteres |

### body (`TelemetryIngestedBody`)

| Campo | Tipo | Observação |
|---|---|---|
| `signal` | `LOGS` / `TRACES` | |
| `productId` | texto | do token |
| `openobserve.organization` / `.stream` | texto | onde buscar |
| `openobserve.batchId` | UUID | `lhamalog_batch_id` |
| `openobserve.recordId` | UUID ou null | só em lote de um registro |
| `openobserve.minTimestampMicros` / `.maxTimestampMicros` | número | faixa de `_timestamp` |
| `serviceNames` | lista | `service.name` distintos, ordenados |
| `priority` / `criticality` | texto | só pela severidade, máximo do lote (research D11) |
| `maxSeverityNumber` | número | |
| `severityCounts` | objeto | contagem por criticidade |
| `recordCount` | número | |
| `bytes` | número | research D12 |
| `receivedAt` | texto ISO | instante do aceite |

Não há corpo, atributos, resource nem scope do registro.

## Limite do stream

`MAXLEN ~ N`, N = `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` / `..._TRACES_MAX_LEN` ou 100000. Valor não numérico ou menor que 1
impede a subida.
