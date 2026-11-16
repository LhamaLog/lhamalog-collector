# Quickstart — métricas e logs do Collector (LLT-187)

## Pré-requisitos

- Infra principal (Mongo/Redis/OpenObserve) se for gerar tráfego OTLP: `make infra-up`
- Stack de observabilidade: **não** usa o `.env` do compose principal

## Subir observabilidade

```bash
make metrics-up
# ou: docker compose -p lhamalog-observability -f infra/local/compose.observability.yaml up -d
```

Validar config (sem subir):

```bash
make metrics-config
```

URLs locais (placeholders):

| Serviço | URL |
|---------|-----|
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin via `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` no `.env.example`) |
| Loki | http://localhost:3100 |
| Collector scrape | http://localhost:9464/actuator/prometheus |

## Rodar o Collector com log file

```powershell
$env:MANAGEMENT_PORT="9464"
$env:LHAMALOG_LOG_FILE="logs/lhamalog-collector.log"
$env:OTLP_ENABLED="false"   # opcional se otel-collector não estiver up
.\mvnw.cmd spring-boot:run
```

Arquivo padrão relativo ao repo: `logs/lhamalog-collector.log` (git-ignored). Alloy tails essa pasta.

## Validação rápida

1. `curl http://localhost:9464/actuator/prometheus` → texto com `jvm_` e, após tráfego, `lhamalog_`
2. Grafana → dashboard LhamaLog Collector → painéis de aceite/latência/filas
3. Enviar OTLP de exemplo; filtrar logs por `cid` na variável do dashboard
4. Explore Loki: `{service="lhamalog-collector"} |= "cid=<id>"`

## Troubleshooting

| Sintoma | Checagem |
|---------|----------|
| Prometheus target DOWN | Collector up? `MANAGEMENT_PORT`? `host.docker.internal`? firewall? |
| Sem `lhamalog_ingestion_accepted_*` | Houve lote aceito? usage/sink ok? |
| Logs não no Loki | Arquivo existe em `logs/`? Alloy healthy? bind mount `../../logs`? |
| 401 no prometheus | SecurityFilterChain de management aplicada? porta correta (management, não 8080)? |

## Parar

```bash
make metrics-down
```
