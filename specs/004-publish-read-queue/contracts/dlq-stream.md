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
| Principal e DLQ falharam | 503, `Retry-After: 5` | `UNAVAILABLE` |
| Demais casos | como no contrato 002 / 001, sem publicar | |

## Garantias

- Entrega pelo menos uma vez: sucesso implica entrada no principal **ou** na DLQ.
- Mesmo `idempotencyId` no principal, nos retries e na DLQ.
- `lastError` nunca carrega senha, token ou URI com credencial.
- Reprocessamento automático da DLQ está fora do escopo.