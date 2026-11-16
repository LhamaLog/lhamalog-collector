# Contrato: referência Redis para o Finder (stream por tenant)

## Envelope (inalterado)

Publicação em `lhamalog:stream:logs` / `lhamalog:stream:traces` (Redis Streams — nomes Redis **não** mudam).

Body `TelemetryIngestedBody` / `openobserve`:

```json
{
  "signal": "LOGS",
  "productId": "…",
  "openobserve": {
    "organization": "lhamalog",
    "stream": "logs_acme",
    "batchId": "…",
    "recordId": null,
    "minTimestampMicros": 0,
    "maxTimestampMicros": 0
  }
}
```

## Mudança LLT-185

| Campo | Antes | Depois |
|-------|-------|--------|
| `openobserve.stream` | valor fixo `logs` / `traces` | stream **por tenant** (ver `tenant-stream-naming.md`) |

- **schemaVersion** do envelope: **sem bump** (só muda o valor da string).
- Nenhum campo novo obrigatório.
- Finder MUST usar o valor de `stream` da mensagem (já deveria); não hardcodar `logs`/`traces` do OO.

## Metadados Redis

`tenantId` / `productId` no metadata do envelope permanecem (LLT-182/183). Sem PII adicional.
