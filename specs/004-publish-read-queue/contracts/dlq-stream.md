# Contrato: DLQ de publicação (`logs:dlq` / `traces:dlq`)

Publicador: lhamalog-collector (`AbstractStreamPublisher`). Consumidor: operação/reprocessamento (fora do
LLT-184). Complementa `specs/002-tenant-product-logs/contracts/log-ingested-stream.md`.

## Escrita

Após esgotar as tentativas no stream principal:

```text
XADD lhamalog:stream:logs:dlq   MAXLEN ~ <N> * idempotencyId <batchId> event log.ingested   origin collector
     schemaVersion 1 publishedAt <iso-utc> metadata <json> body <json>
     targetStream lhamalog:stream:logs attempts <n> lastError <class: msg> failedAt <iso-utc>
XADD lhamalog:stream:traces:dlq MAXLEN ~ <N> * idempotencyId <batchId> event trace.ingested origin collector
     schemaVersion 1 publishedAt <iso-utc> metadata <json> body <json>
     targetStream lhamalog:stream:traces attempts <n> lastError <class: msg> failedAt <iso-utc>
```

- `N` padrão 100000; override `LHAMALOG_MESSAGING_STREAM_LOGS_DLQ_MAX_LEN` /
  `LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN`.
- O formato do stream principal **não muda** (sem bump de `schemaVersion`).

## Exemplo (`logs:dlq`)

```json
{
  "idempotencyId": "0b8e5f3a-1c2d-3e4f-9a8b-7c6d5e4f3a2b",
  "event": "log.ingested",
  "origin": "collector",
  "schemaVersion": "1",
  "publishedAt": "2026-11-04T15:00:00Z",
  "metadata": {"tenantId": "tenant-1", "productId": "product-a", "correlationId": "1b4e..."},
  "body": { "signal": "LOGS", "productId": "product-a", "openobserve": { "batchId": "0b8e5f3a-..." } },
  "targetStream": "lhamalog:stream:logs",
  "attempts": "3",
  "lastError": "MessagePublishException: Redis did not accept the entries for lhamalog:stream:logs",
  "failedAt": "2026-11-04T15:00:00.700Z"
}
```

(`metadata` e `body` são texto JSON no stream; o exemplo os mostra como objetos para leitura.)

## Resposta ao cliente OTLP (após LLT-184)

| Situação | HTTP | gRPC |
|---|---|---|
| Gravado e publicado no stream principal | 200 | OK |
| Principal falhou, DLQ confirmou | 200 | OK |
| Principal e DLQ falharam e o processo respondeu | 503, `Retry-After: 5` | `UNAVAILABLE` |
| Processo morreu antes de responder | sem garantia de 503 | sem garantia de `UNAVAILABLE` |
| Demais casos | como no contrato 002 / 001, sem publicar | |

200 / OK depois da DLQ confirma a escrita na fila de erro. Não significa processamento final pela
Varredura/Finder. Principal e DLQ usam a mesma instância Redis: os dois indisponíveis caem na linha de 503
somente se o processo ainda responder.

## Garantias e limites

- Sucesso da publicação implica entrada confirmada no principal **ou** na DLQ naquele momento. `MAXLEN ~` pode
  descartar depois as entradas mais antigas sem consumo; isso não é retenção temporal nem durabilidade.
- Mesmo `idempotencyId` no principal, nos retries e na DLQ. Timeout ambíguo pode deixar referência nos dois.
  O consumidor deve deduplicar por `idempotencyId` = `batchId`. Esta entrega não verifica essa deduplicação.
- `lastError` recebe redação de padrões conhecidos (userinfo de URI, rótulos `password` / `passwd` / `senha` /
  `secret` / `token` / `apikey` / `api_key` / `authorization` e prefixo `Bearer`). Isso não garante ausência de
  todo segredo arbitrário.
- Não há deadline da requisição nem do `XADD`. O teto de backoff (padrão 2000 ms) limita só as esperas entre
  as 3 tentativas (padrão 100 ms e 200 ms), cada uma e a soma.
- Dono, retenção, persistência AOF/RDB, monitoramento e alertas da DLQ não estão definidos. `N` padrão é
  100000 e o corte é aproximado.
- Reprocessamento automático da DLQ está fora do escopo.
- `event` permanece `log.ingested` ou `trace.ingested`, `schemaVersion` 1.