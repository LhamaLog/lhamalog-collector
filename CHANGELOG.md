# Changelog

Todas as mudanças relevantes do lhamalog-collector ficam neste arquivo.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o projeto usa
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]


### Adicionado (LLT-186)

- Medição de volume por tenant, produto e domínio, outbox Mongo e envio ao Core.
- Documentação em `docs/usage-measurement.md`.
- Estado `SUSPENDED` e métrica `lhamalog.usage.outbox.suspended` para 4xx (exceto 429).
- Reabertura de lote `ABANDONED` e retenção de 7 dias para `SYNCED`/`ABANDONED` terminais.

### Adicionado (LLT-184)

- Retry interno de publicação no Redis Streams: até 3 tentativas totais, com esperas padrão de 100 e 200 ms.
  O teto de backoff (padrão 2000 ms) limita só essas esperas, sem deadline da requisição nem do `XADD`. DLQ
  por domínio quando as tentativas se esgotam.
- DLQs `lhamalog:stream:logs:dlq` e `lhamalog:stream:traces:dlq`, preservando o envelope original e registrando
  destino, tentativas, último erro com redação de padrões conhecidos (não cobre todo segredo arbitrário) e
  instante da falha. Confirmação da DLQ responde sucesso ao exportador e não é processamento final.
  `log.ingested` e `trace.ingested` permanecem na versão 1. `MAXLEN ~` pode descartar entradas antigas sem
  consumo.
- Injeção Spring de `MessagingProperties` e `JsonMapper` em `OtlpReceiverConfiguration`.

### Alterado

- Os documentos no OpenObserve (logs e traces) ganham `lhamalog_batch_id` e `lhamalog_record_id` (UUID v3
  determinístico); os atributos OTel continuam preservados lá.
- Prioridade e criticidade vêm só da severidade: FATAL → CRITICAL, ERROR → HIGH, WARN → MEDIUM, demais → LOW.

### Adicionado

- Evento `log.ingested` (`schemaVersion` 1) em `lhamalog:stream:logs`, com uma entrada por lote de referência
  ao OpenObserve (`signal`, `productId`, `openobserve.organization`, `stream`, `batchId`, `recordId`,
  `minTimestampMicros`, `maxTimestampMicros`, `serviceNames`, `priority`, `criticality`, `maxSeverityNumber`,
  `severityCounts`, `recordCount`, `bytes`, `receivedAt`); o `idempotencyId` é o `batchId` do lote. Contrato em
  `specs/002-tenant-product-logs/contracts/log-ingested-stream.md`.
- Evento `trace.ingested` (`schemaVersion` 1) em `lhamalog:stream:traces`, com o mesmo formato de referência
  por lote; `MAXLEN ~` configurável por `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN` (padrão 100000).
- Publicação no Redis Streams depois da gravação confirmada no OpenObserve; Redis fora responde 503 com
  `Retry-After: 5` (HTTP) ou `UNAVAILABLE` (gRPC).
- Senha obrigatória do Redis no compose local (`REDIS_PASSWORD` em `infra/local/.env`).
