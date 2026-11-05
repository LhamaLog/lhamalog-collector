# Changelog

Todas as mudanças relevantes do lhamalog-collector ficam neste arquivo.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o projeto usa
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Não lançado]

### Alterado

- **BREAKING (consumidor Varredura):** o evento `log.ingested` em `lhamalog:stream:logs` passa para
  `schemaVersion` 2 (LLT-182). Em vez de uma entrada por log com o conteúdo, há **uma entrada por lote** com
  só a referência ao OpenObserve (`signal`, `productId`, `openobserve.organization`, `stream`, `batchId`,
  `recordId`, `minTimestampMicros`, `maxTimestampMicros`, `serviceNames`, `priority`, `criticality`,
  `maxSeverityNumber`, `severityCounts`, `recordCount`, `bytes`, `receivedAt`). O `idempotencyId` passa a
  ser o `batchId` do lote. A Varredura precisa ler a v2 e carregar o conteúdo pelo Finder; consumidores da
  v1 quebram. Contrato em `specs/002-tenant-product-logs/contracts/log-ingested-stream.md`.
- Os documentos no OpenObserve (logs e traces) ganham `lhamalog_batch_id` e `lhamalog_record_id` (UUID v3
  determinístico); os atributos OTel continuam preservados lá.
- Prioridade e criticidade vêm só da severidade: FATAL → CRITICAL, ERROR → HIGH, WARN → MEDIUM, demais → LOW.

### Adicionado

- Evento `trace.ingested` (`schemaVersion` 1) em `lhamalog:stream:traces`, com o mesmo formato de referência
  por lote; `MAXLEN ~` configurável por `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN` (padrão 100000).
- Publicação no Redis Streams depois da gravação confirmada no OpenObserve; Redis fora responde 503 com
  `Retry-After: 5` (HTTP) ou `UNAVAILABLE` (gRPC).
- Senha obrigatória do Redis no compose local (`REDIS_PASSWORD` em `infra/local/.env`).
