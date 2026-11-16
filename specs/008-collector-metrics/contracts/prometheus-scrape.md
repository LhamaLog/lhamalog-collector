# Contract: Prometheus scrape endpoint

## Endpoint

- **URL**: `http://<host>:${MANAGEMENT_PORT:9464}/actuator/prometheus`
- **Method**: GET
- **Auth**: none (permitAll na SecurityFilterChain de management endpoints)
- **Content-Type**: `text/plain; version=0.0.4` (ou OpenMetrics text)

## Exposed management endpoints

`management.endpoints.web.exposure.include=health,info,prometheus`

Não expor: `env`, `beans`, `configprops`, `heapdump`, etc.

## Expected metric families (após tráfego / refresh)

Nomes Prometheus (Micrometer: dots → underscores; counters + `_total`):

```text
lhamalog_ingestion_accepted_records_total{application,signal,protocol}
lhamalog_ingestion_accepted_bytes_total{application,signal,protocol}
lhamalog_ingestion_latency_seconds_bucket{application,signal,protocol,outcome,le}
lhamalog_ingestion_latency_seconds_count{...}
lhamalog_ingestion_latency_seconds_sum{...}
lhamalog_queue_size{application,signal}
lhamalog_dlq_size{application,signal}
```

Também presentes (pré-existentes / JVM):

```text
lhamalog_ingestion_refused_total{reason}
lhamalog_quota_*_total
lhamalog_usage_outbox_*
jvm_memory_used_bytes
```

## Security contract (tests)

| Request | Expected |
|---------|----------|
| GET management `/actuator/prometheus` | 200, body contains `lhamalog_` após incremento |
| GET management `/actuator/health` | 200 |
| GET management `/actuator/env` | 404 ou não autorizado / não mapeado |
| GET main `:8080` non-actuator protected path | authenticated (401/403 sem credenciais) |

## Tag safety

Nenhuma série scrapeada MUST conter label `tenantId`, `productId` ou `token`.
