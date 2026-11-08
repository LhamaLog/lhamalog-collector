# Data Model: Publicar logs na fila de leitura (LLT-184)

O modelo do stream principal permanece o de `specs/002-tenant-product-logs/data-model.md`. Este documento
acrescenta só a política de retry e a entrada da DLQ.

## Política de retry (configuração)

| Campo | Padrão | Variável |
|---|---|---|
| tentativas máximas no stream principal | 3 | `LHAMALOG_MESSAGING_PUBLISH_MAX_ATTEMPTS` |
| backoff inicial (ms) | 100 | `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_INITIAL_MS` |
| multiplicador | 2 | `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_MULTIPLIER` |
| teto de espera acumulada (ms) | 2000 | `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` |

Valores não positivos (ou não numéricos, ou multiplicador < 1) impedem a subida.

## Entrada da DLQ (`lhamalog:stream:logs:dlq` / `lhamalog:stream:traces:dlq`)

Campos texto. Os sete primeiros espelham o envelope do stream principal; os quatro seguintes descrevem a falha.

| Campo | Valor |
|---|---|
| `idempotencyId` | mesmo `batchId` do envelope original |
| `event` | `log.ingested` / `trace.ingested` |
| `origin` | `collector` |
| `schemaVersion` | `1` (logs e traces) |
| `publishedAt` | instante ISO do envelope original (da tentativa de publicação) |
| `metadata` | JSON do envelope original |
| `body` | JSON do envelope original |
| `targetStream` | stream principal que falhou, ex. `lhamalog:stream:logs` |
| `attempts` | número de tentativas feitas no principal (ex. `3`) |
| `lastError` | `ClassSimpleName: message` (sem segredo) |
| `failedAt` | instante ISO-8601 UTC em que a DLQ foi escrita |

## Limite da DLQ

`MAXLEN ~ N`, N = `LHAMALOG_MESSAGING_STREAM_LOGS_DLQ_MAX_LEN` /
`LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN` ou 100000.