# Catálogo de métricas do Collector

Documentação das métricas operacionais do LhamaLog Collector (existentes + LLT-187).  
Nenhuma métrica usa `tenantId` / `productId` / `token` como tag.

Convenção Prometheus (Micrometer): dots → underscores; counters ganham sufixo `_total`.

Tag comum: `application` (= `spring.application.name`).

## Novas (LLT-187 / Q4 Michel)

| Micrometer | Tipo | Tags | Significado | Prometheus | PromQL exemplo | Painel |
|------------|------|------|-------------|------------|----------------|--------|
| `lhamalog.ingestion.accepted.records` | counter | signal, protocol | Records aceitos após sink/publish ok | `lhamalog_ingestion_accepted_records_total` | `sum by (signal,protocol) (rate(...[5m]))` | Accepted records/s |
| `lhamalog.ingestion.accepted.bytes` | counter | signal, protocol | Bytes canônicos do lote aceito (mesma medida de usage) | `lhamalog_ingestion_accepted_bytes_total` | `sum by (signal,protocol) (rate(...[5m]))` | Accepted bytes/s |
| `lhamalog.ingestion.latency` | timer/histogram | signal, protocol, outcome | Duração do handling OTLP no receiver | `lhamalog_ingestion_latency_seconds_*` | `histogram_quantile(0.95, sum by (le,signal,protocol) (rate(..._bucket[5m])))` | Latency p50/p95/p99 |
| `lhamalog.queue.size` | gauge | signal | XLEN `lhamalog:stream:logs\|traces` (cache 15s) | `lhamalog_queue_size` | `lhamalog_queue_size` | Queue size |
| `lhamalog.dlq.size` | gauge | signal | XLEN DLQ correspondente | `lhamalog_dlq_size` | `lhamalog_dlq_size` | DLQ size (threshold > 0) |

Valores: `signal`=logs\|traces; `protocol`=http\|grpc; `outcome`=accepted\|refused\|failed.

## Recusas (LLT-183)

| Micrometer | Tipo | Tags | Prometheus | PromQL | Painel |
|------------|------|------|------------|--------|--------|
| `lhamalog.ingestion.refused` | counter | reason | `lhamalog_ingestion_refused_total` | `sum by (reason) (rate(...[5m]))` | Refusals by reason |

Reasons: `rate_limit`, `rate_limit_blocked`, `batch_too_large`, `insufficient_balance`, `subscription_suspended`, `quota_unavailable`, `quota_unavailable_exhausted`, `rate_limit_store_unavailable`, `quota_unavailable_attempt_store_unavailable`.

## Quota

Counters sem tags de identidade (`lhamalog.quota.*`):

`authorized`, `unlimited`, `insufficient`, `no_plan`, `core_unavailable`, `released`, `post_debit_write_failure`, `refund_enqueued`, `refund_succeeded`, `refund_retry`, `refund_suspended`.

## Usage outbox

| Micrometer | Tipo | Tags |
|------------|------|------|
| `lhamalog.usage.outbox.transitions` | counter | state |
| `lhamalog.usage.outbox.retries` | counter | — |
| `lhamalog.usage.outbox.suspended` | counter | — |
| `lhamalog.usage.outbox.pending.over_grace` | counter | — |
| `lhamalog.usage.outbox.ambiguous` | counter | — |
| `lhamalog.usage.outbox.attempts` | gauge | — |
| `lhamalog.usage.outbox.pending.age.seconds` | gauge | — |
| `lhamalog.usage.outbox.evidence.ttl.seconds` | gauge | — |

## JVM / HTTP (framework)

- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `jvm_threads_*`, `process_cpu_usage`
- `http_server_requests_seconds_*` — cobre sobretudo Actuator/MVC; latência OTLP = `lhamalog.ingestion.latency`

## Scrape

`GET http://localhost:${MANAGEMENT_PORT:9464}/actuator/prometheus`

Runbook: [docs/observability-runbook.md](../../docs/observability-runbook.md).
