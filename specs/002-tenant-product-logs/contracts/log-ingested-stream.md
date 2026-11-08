# Contrato: eventos `log.ingested` e `trace.ingested` (referência ao OpenObserve)

**Responsável**: michelbocchijr@gmail.com

Publicador: lhamalog-collector. Consumidor: Varredura (Finder carrega o conteúdo). Base: rascunho de ADR de
mensageria com Redis Streams (status Proposta). Revisão 2026-11-02: o corpo é só uma referência.

## Escrita

```text
XADD lhamalog:stream:logs   MAXLEN ~ <N> * idempotencyId <batchId> event log.ingested   origin collector
     schemaVersion 1 publishedAt <iso-utc> metadata <json> body <json>
XADD lhamalog:stream:traces MAXLEN ~ <N> * idempotencyId <batchId> event trace.ingested origin collector
     schemaVersion 1 publishedAt <iso-utc> metadata <json> body <json>
```

- Uma entrada por lote aceito.
- Só depois de token válido, registro válido e gravação confirmada no OpenObserve.
- `N` padrão 100000; override `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` / `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN`.

## Exemplo

```json
{
  "idempotencyId": "0b8e5f3a-1c2d-3e4f-9a8b-7c6d5e4f3a2b",
  "event": "log.ingested",
  "origin": "collector",
  "schemaVersion": "1",
  "publishedAt": "2026-11-01T15:00:00Z",
  "metadata": {"tenantId": "tenant-1", "productId": "product-a", "correlationId": "1b4e...", "traceId": "0af7651916cd43dd8448eb211c80319c"},
  "body": {
    "signal": "LOGS",
    "productId": "product-a",
    "openobserve": {
      "organization": "lhamalog",
      "stream": "logs",
      "batchId": "0b8e5f3a-1c2d-3e4f-9a8b-7c6d5e4f3a2b",
      "recordId": null,
      "minTimestampMicros": 1793545200000000,
      "maxTimestampMicros": 1793545200950000
    },
    "serviceNames": ["checkout"],
    "priority": "HIGH",
    "criticality": "ERROR",
    "maxSeverityNumber": 17,
    "severityCounts": {"INFO": 987, "WARN": 10, "ERROR": 3},
    "recordCount": 1000,
    "bytes": 52341,
    "receivedAt": "2026-11-01T15:00:00.120Z"
  }
}
```

(`metadata` e `body` aparecem como objetos para leitura; no stream são texto JSON.)

## Como o Finder carrega o conteúdo

```json
POST /api/{organization}/_search
{"query": {"sql": "SELECT * FROM \"logs\" WHERE lhamalog_batch_id = '<batchId>' ORDER BY lhamalog_record_id",
           "start_time": <minTimestampMicros>, "end_time": <maxTimestampMicros + 1>, "from": <offset>, "size": 1000}}
```

- **Não** usar `size = recordCount`: um reenvio após 503 regrava o lote com os mesmos ids, então o
  OpenObserve pode ter mais de `recordCount` documentos do lote e o limite cortaria registros.
- Paginar (`from` += `size`) até vir menos que `size` hits e deduplicar por `lhamalog_record_id` (ficar com
  um documento por id) **antes** de contar. O lote está completo quando o número de ids distintos é
  `recordCount`. Para só conferir: `SELECT COUNT(DISTINCT lhamalog_record_id) ... WHERE lhamalog_batch_id = '<batchId>'`.
- Com `recordId` (lote de um registro): `WHERE lhamalog_record_id = '<recordId>'`, ficando com um documento.

## Eventos seguintes (fora do collector)

Depois da varredura de dados sensíveis e da validação de prompt-injection, a Varredura publica
`log.checked` / `trace.checked`. Contrato a definir no módulo da Varredura.

## Garantias para o consumidor

- Entrega pelo menos uma vez; um reenvio do cliente após 503 repete a entrada com o mesmo `idempotencyId`;
  descartar repetidos (SET NX com TTL de 25 h, conforme o ADR).
- `correlationId` é aleatório por chamada de exportação e **não** se repete no reenvio; não use para
  deduplicar.
- `bytes` conta só o tamanho serializado dos registros aceitos (sem resource e scope); é uma estimativa de
  volume, não o tamanho da requisição.
- Dois lotes legítimos com conteúdo idêntico (mesmos bytes, inclusive horário) têm o mesmo `batchId` e são
  tratados como reenvio, por decisão (research D13).
- Atributos OTel (recurso, escopo, registro) ficam preservados no OpenObserve; a fila leva só a referência e
  o Finder carrega o conteúdo por ela.
- `tenantId` é a fonte de verdade do dono e fica só em `metadata`.
- Nenhum conteúdo de log ou span na fila.
- Mudança incompatível no `body` sobe `schemaVersion`.

## Resposta ao cliente OTLP

| Situação | HTTP | gRPC |
|---|---|---|
| Gravado e publicado | 200 | OK |
| Redis fora ou sem confirmar | 503, `Retry-After: 5` | `UNAVAILABLE` |
| Demais casos | como em `specs/001-otlp-receiver/contracts/otlp-export.md`, sem publicar | |
