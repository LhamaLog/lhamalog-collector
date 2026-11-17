# Contract: Dashboard, datasources e labels Loki

## Grafana datasources (provisioned)

| Name | UID | Type |
|------|-----|------|
| Prometheus | `prometheus` | prometheus → `http://prometheus:9090` |
| Loki | `loki` | loki → `http://loki:3100` |

## Loki label policy

**Allowed labels**: `service`, `level`, optionally `env`.

**Forbidden labels**: `tenantId`, `tenant_id`, `tenant`, `productId`, `product_id`, `token`, qualquer id de cliente.

**Structured metadata / parsed fields** (não labels): `cid`, `event`, `logger`.

## Dashboard `lhamalog-collector`

Variáveis:

- `cid` — textbox (default vazio)
- `level` — opcional multi/filter

Painéis mínimos:

1. Refusals by reason — `sum by (reason) (rate(lhamalog_ingestion_refused_total[5m]))`
2. Quota counters (authorized, insufficient, no_plan, core_unavailable, unlimited, released, refund_*)
3. Usage outbox (transitions by state, retries, suspended, pending age, attempts)
4. Accepted records/s and bytes/s by signal/protocol
5. Latency p50/p95/p99 by signal/protocol (`histogram_quantile`)
6. Outcome breakdown of latency count
7. Queue size and DLQ size by signal (threshold hint DLQ > 0)
8. HTTP server requests rate + p95 (`http_server_requests_seconds_*`)
9. JVM: heap, GC pause, threads, process CPU
10. Logs panel: `{service="lhamalog-collector"} |= "$cid"` (+ level filter)

## Explore example (docs)

```logql
{service="lhamalog-collector"} |= "cid=<id>"
```
