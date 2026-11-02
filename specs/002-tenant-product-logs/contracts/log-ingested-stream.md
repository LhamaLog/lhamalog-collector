# Contrato: evento `log.ingested` no stream `lhamalog:stream:logs`

Publicador: lhamalog-collector. Consumidor: Varredura. Base: rascunho de ADR de mensageria com Redis Streams
(status Proposta).

## Escrita

```text
XADD lhamalog:stream:logs MAXLEN ~ <N> * idempotencyId <uuid> event log.ingested origin collector
     schemaVersion 1 publishedAt <iso-utc> metadata <json> body <json>
```

- Uma entrada por log aceito, todas do lote num pipeline.
- Só depois de token válido, registro válido e gravação confirmada no OpenObserve.
- `N` padrão 100000; override `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN`.

## Exemplo

```json
{
  "idempotencyId": "6f1c2c1e-6c1b-3a51-9d3e-2b7a0f0d6c11",
  "event": "log.ingested",
  "origin": "collector",
  "schemaVersion": "1",
  "publishedAt": "2026-11-01T15:00:00Z",
  "metadata": {"tenantId": "tenant-1", "productId": "product-a", "correlationId": "1b4e...", "traceId": "0af7651916cd43dd8448eb211c80319c"},
  "body": {
    "receivedAt": "2026-11-01T15:00:00.120Z",
    "timeUnixNano": "1793545200000000000",
    "observedTimeUnixNano": null,
    "severityNumber": 9,
    "severityText": "INFO",
    "body": "order paid",
    "attributes": {"order.id": "o-42"},
    "traceId": "0af7651916cd43dd8448eb211c80319c",
    "spanId": "b7ad6b7169203331",
    "flags": 1,
    "resource": {"serviceName": "checkout", "attributes": {"service.name": "checkout"}},
    "scope": {"name": "app.logger", "version": "1.2.3", "attributes": {}}
  }
}
```

(`metadata` e `body` aparecem como objetos para leitura; no stream são texto JSON.)

## Garantias para o consumidor

- Entrega pelo menos uma vez. Um reenvio do cliente após 503 repete as entradas com o mesmo
  `idempotencyId`; descartar repetidos (SET NX com TTL de 25 h, conforme o ADR).
- `tenantId` é a fonte de verdade do dono e fica só em `metadata`.
- Mudança incompatível no `body` sobe `schemaVersion`.

## Resposta ao cliente OTLP

| Situação | HTTP | gRPC |
|---|---|---|
| Gravado e publicado | 200 | OK |
| Redis fora ou sem confirmar | 503, `Retry-After: 5` | `UNAVAILABLE` |
| Demais casos | como em `specs/001-otlp-receiver/contracts/otlp-export.md`, sem publicar | |
