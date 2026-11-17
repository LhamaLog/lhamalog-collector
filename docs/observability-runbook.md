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

Grafana **sem** acesso anônimo: entre com `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` do `infra/local/.env` (o compose para com erro se a senha faltar). Prometheus (9090), Loki (3100) e Grafana (3000) só escutam em `127.0.0.1`. O Loki roda com `auth_enabled: false` (single-tenant local) porque só fica em loopback e o Alloy usa a rede interna do compose; em ambiente compartilhado o certo é `auth_enabled: true` com proxy autenticado.

## Porta de management (segurança)

- Default `MANAGEMENT_PORT=9464` (evita conflito com Core em 8081).
- `MANAGEMENT_ADDRESS` (default `127.0.0.1` em **todos** os perfis, inclusive dev/local): interface da porta de management. O Prometheus do compose chega nela por `host.docker.internal` (Docker Desktop encaminha para o loopback do host). Use `0.0.0.0` só se o Docker não alcançar o host (ex.: Linux sem host-gateway) e nunca em rede compartilhada. **Não** expor publicamente.
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

## Outcome `partial` e lote parcialmente aceito

- `lhamalog.ingestion.latency{outcome}`: `accepted` | `partial` | `refused` | `failed`. `partial` = parte dos registros gravada e parte rejeitada (validação local ou recusa do OpenObserve).
- Na aceitação parcial do OpenObserve, `accepted.records` soma só os registros gravados. O OpenObserve não diz quais foram, então `accepted.bytes` é uma estimativa: bytes entregues × gravados ÷ entregues (arredondado para baixo). A cobrança continua pelo débito da reserva, não por essa métrica.

## Railway (IaC preparado, nada implantado)

Arquivos: `.railway/railway.ts` (Infrastructure as Code, partial `observability-stack`) e `infra/railway/<serviço>/` (Dockerfile + config de cada serviço; contexto de build = raiz do repositório). O formato antigo `railway.json`/`railway.toml` (Config as Code) está obsoleto na Railway, não aceita serviço novo e deixa de ser lido em 01/12/2026, por isso não foi usado.

**Nada foi criado nem implantado.** Implantar exige OK do Michel.

### Serviços

| Serviço | Imagem base | Porta | Healthcheck | Volume | Domínio público |
|---|---|---|---|---|---|
| `prometheus` | `prom/prometheus:v3.5.0` | 9090 | `/-/ready` (120 s) | `prometheus-data` 1 GB em `/prometheus` (retenção 7d / 900 MB) | **não** |
| `loki` | `grafana/loki:3.5.3` | 3100 | `/ready` (300 s) | `loki-data` 2 GB em `/loki` (retenção 168 h) | **não** |
| `alloy` | `grafana/alloy:v1.10.2` | 12345 (UI) / 4318 (OTLP HTTP) | `/-/ready` (120 s) | — | **não** |
| `grafana` | `grafana/grafana:12.2.0` | 3000 | `/api/health` (120 s) | `grafana-data` 512 MB em `/var/lib/grafana` | só este, gerado no dashboard |

Todos com restart `ON_FAILURE` (até 10 tentativas), build por `DOCKERFILE` e `watchPatterns` só da própria pasta. Branch `Development`; no ambiente `production`, `main`. `RAILWAY_RUN_UID=0` nos serviços com volume (o volume da Railway monta como root).

### Variáveis

| Serviço | Variável | Valor |
|---|---|---|
| todos | `PORT` | porta do serviço (o healthcheck da Railway usa essa porta) |
| prometheus | `COLLECTOR_METRICS_TARGET` | `lhamalog-collector.railway.internal:9464` (nome do serviço do Collector na Railway) |
| prometheus | `PROMETHEUS_RETENTION` | `7d` |
| alloy | `LOKI_PUSH_URL` | `http://loki.railway.internal:3100/loki/api/v1/push` |
| grafana | `GF_SECURITY_ADMIN_USER` | `admin` |
| grafana | `GF_SECURITY_ADMIN_PASSWORD` | **segredo**: `preserve()` no IaC. Criar antes como variável (selada) do serviço no dashboard; nunca em arquivo. Sem ela (ou vazia, ou `admin`) o entrypoint `infra/railway/grafana/entrypoint.sh` aborta com `event=grafana.admin_password.missing` e o Grafana não sobe: nada de `admin/admin` gravado no volume |
| grafana | `GF_AUTH_ANONYMOUS_ENABLED` / `GF_USERS_ALLOW_SIGN_UP` | `false` |
| grafana | `GF_SECURITY_COOKIE_SECURE` | `true` (acesso só pelo domínio HTTPS) |
| grafana | `PROMETHEUS_URL` / `LOKI_URL` | `http://prometheus.railway.internal:9090` / `http://loki.railway.internal:3100` |
| Collector (serviço dele, fora deste IaC) | `MANAGEMENT_ADDRESS` | `::` (o default `127.0.0.1` não é alcançável por outro serviço) |

### Rede privada

- Prometheus, Loki e Alloy **sem** domínio público e sem TCP proxy: só `*.railway.internal`, rede privada (Wireguard) isolada por projeto e ambiente. Nunca gerar domínio para eles.
- Loki com `auth_enabled: false` só é aceitável por isso: ninguém fora da rede privada do projeto chega nele. A porta de management do Collector (9464, sem autenticação) também nunca recebe domínio público.
- Os serviços escutam em todas as interfaces (IPv4 e IPv6); ambientes antigos da Railway só resolvem `railway.internal` em IPv6.
- O Grafana chega no Prometheus e no Loki pelas URLs privadas acima (datasources provisionados em `infra/railway/grafana/provisioning`, com os dashboards de `infra/local/observability/grafana/dashboards` embutidos na imagem).

### Como o Collector alimentaria o módulo

- **Métricas:** o Prometheus raspa `http://lhamalog-collector.railway.internal:9464/actuator/prometheus` (pull). Basta o Collector rodar com `MANAGEMENT_ADDRESS=::`.
- **Logs:** na Railway não há arquivo de log compartilhado para o Alloy ler. O Alloy recebe logs OTLP/HTTP em `http://alloy.railway.internal:4318/v1/logs` e manda para o Loki. Falta no Collector um exportador OTLP de logs (ex.: appender OpenTelemetry do Logback) — fica como follow-up, não está nesta mudança. Enquanto isso, os logs do Collector seguem só no log da própria Railway.

### Como implantar depois (com OK do Michel)

1. Instalar a CLI da Railway (5.42.1 ou mais nova) e `npm install` em `.railway/` (pacote `railway` 3.17.0).
2. `railway link` no projeto/ambiente certo.
3. Criar a variável selada `GF_SECURITY_ADMIN_PASSWORD` no serviço `grafana` (dashboard).
4. `railway config plan` e revisar o plano (4 serviços, 3 volumes, nada fora do grupo `Observability`).
5. `railway config apply` (sem `--yes`).
6. Gerar domínio público **só** para o `grafana` (porta 3000) e configurar `MANAGEMENT_ADDRESS=::` no serviço do Collector.

### Custo

Nada foi implantado, então não há custo agora. Na Railway a cobrança é por uso (CPU, memória, volume e tráfego de saída; tráfego na rede privada não conta como egress). São quatro serviços sempre ligados e 3,5 GB de volume: estimar no painel de uso da Railway antes de aplicar.

### Validação local feita (16/11/2026)

`tsc` do `railway.ts` com o pacote `railway` 3.17.0 e avaliação do programa (Development e production); `docker build` das quatro imagens; containers numa rede descartável: Prometheus, Loki, Alloy e Grafana prontos, Grafana 401 sem credencial, datasources Prometheus e Loki com health OK, dashboard provisionado, log OTLP enviado ao Alloy chegando no Loki. Containers e imagens removidos.
