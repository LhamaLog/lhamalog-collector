# Runbook — métricas e logs operacionais do Collector (LLT-187)

## O que é o quê

| Sistema | Função |
|---------|--------|
| **OpenObserve** (`compose.yaml`) | Sink da telemetria **ingerida** de clientes (ADR_168) |
| **Prometheus + Grafana + Loki + Alloy** (`compose.observability.yaml`) | Métricas/logs **do próprio Collector** |

Catálogo completo de métricas: [specs/008-collector-metrics/metricas.md](../specs/008-collector-metrics/metricas.md).

## Subir / parar

```bash
make metrics-up
make metrics-ps
make metrics-logs
make metrics-down
make metrics-config   # valida YAML sem subir
```

Projeto Docker: `lhamalog-observability` — **não** usa o `.env` de secrets do compose principal.

## URLs e credenciais (placeholders)

| Serviço | URL | Credencial |
|---------|-----|------------|
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` (ex.: `admin` / `change-me`) |
| Loki | http://localhost:3100 | — |
| Collector scrape | http://localhost:9464/actuator/prometheus | público (só health/info/prometheus) |

Anonymous Viewer no Grafana está habilitado **somente** no compose local.

## Porta de management (segurança)

- Default `MANAGEMENT_PORT=9464` (evita conflito com Core em 8081).
- Em **prod**: `management.server.address=127.0.0.1` (ou rede interna). **Não** expor publicamente.
- Em local Docker Desktop o bind default escuta em todas as interfaces para o Prometheus alcançar via `host.docker.internal`.
- SecurityFilterChain libera só `health`, `info`, `prometheus`. Demais Actuator não são expostos. Porta 8080 permanece autenticada.

## Collector com arquivo de log

```powershell
$env:MANAGEMENT_PORT="9464"
$env:LHAMALOG_LOG_FILE="logs/lhamalog-collector.log"
.\mvnw.cmd spring-boot:run
```

O Alloy faz tail de `../../logs` (bind mount). Formato = princípio VI (mesmo pattern de console, sem cores).

**Por que arquivo + Alloy (não OTLP logs / appender Loki):** sem nova dependência runtime, linha preservada exatamente, funciona para qualquer processo Java local.

## PromQL úteis

```promql
sum by (signal, protocol) (rate(lhamalog_ingestion_accepted_records_total[5m]))
histogram_quantile(0.95, sum by (le, signal, protocol) (rate(lhamalog_ingestion_latency_seconds_bucket[5m])))
lhamalog_queue_size
lhamalog_dlq_size
sum by (reason) (rate(lhamalog_ingestion_refused_total[5m]))
```

## LogQL / Explore

```logql
{service="lhamalog-collector"} |= "cid=<id>"
{service="lhamalog-collector", level="WARN"}
```

No dashboard, variável textbox `cid` filtra o painel de logs.

## Troubleshooting

| Sintoma | O que checar |
|---------|----------------|
| Target DOWN no Prometheus | Collector no ar? porta 9464? `host.docker.internal` (extra_hosts host-gateway)? firewall Windows? |
| Sem `lhamalog_ingestion_accepted_*` | Houve lote aceito (sink/publish ok)? |
| Logs não aparecem no Loki | Existe `logs/lhamalog-collector.log`? Alloy healthy? mount `../../logs`? |
| 401 em `/actuator/prometheus` | Está na porta de **management**, não na 8080? |
| Gauge NaN / estagnado | Redis down? ver WARN `event=metrics.queue_size.refresh_failed` (rate-limited) |

## Nota sobre `http.server.requests`

Os receivers OTLP são JDK HttpServer/gRPC (não Spring MVC). Painéis `http_server_requests_*` cobrem sobretudo tráfego Actuator/MVC. Latência OTLP = `lhamalog.ingestion.latency`.
