# Runbook — métricas e logs operacionais do Collector (LLT-187)

## O que é o quê

| Sistema | Função |
|---------|--------|
| **OpenObserve** (`compose.yaml`) | Sink da telemetria **ingerida** de clientes (ADR_168) |
| **Prometheus + Grafana + Alloy** (`compose.observability.yaml`) | Métricas e logs **do próprio Collector**; os logs vão para o OpenObserve, org `default`, stream `lhamalog_platform` |

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
| OpenObserve (logs da plataforma) | http://localhost:5080 (org `default`, stream `lhamalog_platform`) | `OPENOBSERVE_USERNAME` / `OPENOBSERVE_PASSWORD` do `infra/local/.env` |
| Collector scrape | http://localhost:9464/actuator/prometheus | público (só health/info/prometheus) |

Grafana **sem** acesso anônimo: entre com `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` do `infra/local/.env` (o compose para com erro se a senha faltar). Prometheus (9090) e Grafana (3000) só escutam em `127.0.0.1`. O Alloy usa `OPENOBSERVE_AUTH_BASIC` do mesmo `.env` (base64 de `email:passcode de ingestão` do OpenObserve, com **ou sem** o prefixo `Basic ` — o Alloy remove o prefixo sem diferenciar maiúsculas, apara espaços e monta `Basic <valor>`; o compose para com erro se faltar). O datasource do Grafana precisa pesquisar, e o passcode de ingestão só vale nos endpoints de ingestão: por isso o Grafana usa `OPENOBSERVE_USERNAME` / `OPENOBSERVE_PASSWORD`. Nenhum desses valores vai para arquivo versionado.

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

- O estado do Alloy (posição de leitura do arquivo) fica no volume `alloy-data` (`/var/lib/alloy/data`, `--storage.path`): recriar o container continua de onde parou e não reenvia o arquivo inteiro. `docker compose ... down -v` apaga esse estado (aí o arquivo é relido do início).
- Os testes (`mvnw verify`) **nunca** escrevem em `logs/`: o `maven-surefire-plugin` fixa `logging.file.name=target/test-logs/lhamalog-collector-tests.log` (propriedade de sistema, vale mesmo com `SPRING_PROFILES_ACTIVE=local` ou `LHAMALOG_LOG_FILE` no shell). Assim nenhuma linha de teste chega ao stream `lhamalog_platform`.

**Rota dos logs (local):** arquivo → Alloy (`loki.source.file`, só o leitor de arquivo, sem Loki) → OTLP/HTTP (`otelcol.exporter.otlphttp "openobserve"`) → `OPENOBSERVE_URL/api/default` com os headers `Authorization: Basic <OPENOBSERVE_AUTH_BASIC sem prefixo e aparado>` e `stream-name: lhamalog_platform`. Cada registro leva `service` (módulo), `level`, `cid` (correlation id), `logger`, `event` e a linha completa em `body`. Sem dependência runtime nova no Collector.

### Logs da plataforma nunca caem em stream de tenant

- Org e stream são **fixos** na config do Alloy (`default` / `lhamalog_platform`; na Railway por variável com esses defaults), nunca lidos do registro.
- Os dados de tenant ficam em outra organização (`OPENOBSERVE_ORGANIZATION`, default `lhamalog`, streams `logs_<tenant>` / `traces_<tenant>`).
- O Collector **recusa subir** com `lhamalog.otlp.openobserve.organization=default` (`OtlpReceiverProperties.OpenObserve.PLATFORM_ORGANIZATION`, teste em `OtlpReceiverPropertiesTest`). Assim nenhum tenant escreve na org dos logs da plataforma, e o stream `lhamalog_platform` nunca vira stream de tenant.

## PromQL úteis

```promql
sum by (signal, protocol) (rate(lhamalog_ingestion_accepted_records_total[5m]))
histogram_quantile(0.95, sum by (le, signal, protocol) (rate(lhamalog_ingestion_latency_seconds_bucket[5m])))
lhamalog_queue_size
lhamalog_dlq_size
sum by (reason) (rate(lhamalog_ingestion_refused_total[5m]))
```

## SQL no OpenObserve (org `default`)

```sql
SELECT _timestamp, level, cid, body FROM "lhamalog_platform" WHERE service = 'lhamalog-collector' AND cid = '<id>'
SELECT _timestamp, body FROM "lhamalog_platform" WHERE level = 'WARN'
```

No dashboard, a variável textbox `cid` filtra os painéis de logs.

## Dashboards provisionados (Grafana)

Ficam em `infra/local/observability/grafana/dashboards/` (pasta **LhamaLog**). São os mesmos no local e na Railway: a imagem `infra/railway/grafana/Dockerfile` copia essa pasta. Nenhum usa `tenantId` como tag ou label.

| Dashboard (uid) | Para quê | Painéis |
|---|---|---|
| **LhamaLog — Controle de filas** (`lhamalog-filas`) | Fila e DLQ do Collector (Redis Streams) | fila (stream) com cores por limite (70 mil laranja, 90 mil vermelho); ocupação % do MAXLEN; DLQ (vermelho se > 0); entrada/s; tamanho da fila e da DLQ no tempo com linhas de limite; entrada x saída estimada; crescimento líquido (backlog); entrada por resultado do lote; nota de leitura |
| **LhamaLog — Métricas por módulo** (`lhamalog-modulos`) | Uma linha por módulo: Collector, Core, Varredura, Finder, Ocorrência | visão geral por `job` (alvos UP/DOWN, heap, CPU); Collector: registros e bytes aceitos/s, latência p95, recusas/s, falhas/s, % de erro, throughput por signal/protocol, latência p50/p95/p99 e p95 por signal/protocol, outcome, recusas por motivo, erros, logs WARN/ERROR (logback), retenção OpenObserve, quota/outbox, HTTP Actuator e JVM; Core/Varredura/Finder/Ocorrência: nota "sem métricas ainda", alvo, HTTP e heap ("sem dados") |
| **LhamaLog — Logs por módulo** (`lhamalog-logs`) | Logs da plataforma no OpenObserve (datasource `openobserve`, plugin oficial `openobserve` (`zo_gp` 1.0.0, SHA-256 fixado no Dockerfile), provisionado) | volume por nível; eventos (`event=`) no período; tabela de logs filtrada; ajuda |
| **LhamaLog Collector** (`lhamalog-collector`) | Painel original da LLT-187 | mantido, sem o painel de logs do Loki |

Variáveis: `signal` (filas); `job` = módulo (métricas por módulo); `module` (campo `service`), `level`, `cid` (correlation id exato, textbox) e `search` (trecho do texto, sem diferenciar maiúsculas) nos logs.

Leitura da fila: `lhamalog_queue_size` é o XLEN da stream (corte por `MAXLEN` ~100 mil), não o lag do consumidor. A "saída estimada" = entrada (1 mensagem por lote aceito) menos o crescimento da stream. O lag real do grupo de consumo depende de métricas da Varredura, que ainda não expõe métricas.

Módulo novo: exponha `/actuator/prometheus` e adicione um `job_name` `lhamalog-<modulo>` no `prometheus.yml` (local e Railway); a linha dele no dashboard passa a ter dados. Para logs, mande o `service.name` (Railway) ou um alvo de arquivo com label `service` (local).
## Troubleshooting

| Sintoma | O que checar |
|---------|----------------|
| Target DOWN no Prometheus | Collector no ar? porta 9464? `host.docker.internal` (extra_hosts host-gateway)? firewall Windows? |
| Sem `lhamalog_ingestion_accepted_*` | Houve lote aceito (sink/publish ok)? |
| Logs não aparecem no OpenObserve | Existe `logs/lhamalog-collector.log`? Alloy healthy? mount `../../logs`? `OPENOBSERVE_AUTH_BASIC` certo (401 no log do Alloy)? |
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
| `openobserve` | `public.ecr.aws/zinclabs/openobserve:v1.1.2` | 5080 | `/healthz` (120 s) | `openobserve-data` 2 GB em `/data` | **não** (UI atrás do login do OpenObserve, só pela rede privada) |
| `alloy` | `grafana/alloy:v1.10.2` | 12345 (UI) / 4318 (OTLP HTTP) | `/-/ready` (120 s) | `alloy-data` 256 MB em `/var/lib/alloy/data` (estado/WAL do Alloy) | **não** |
| `grafana` | `grafana/grafana:12.2.0` | 3000 | `/api/health` (120 s) | `grafana-data` 512 MB em `/var/lib/grafana` | só este, gerado no dashboard |

Todos com restart `ON_FAILURE` (até 10 tentativas), build por `DOCKERFILE` e `watchPatterns` só da própria pasta. Branch `Development`; no ambiente `production`, `main`. `RAILWAY_RUN_UID=0` nos serviços com volume (o volume da Railway monta como root).

### Variáveis

| Serviço | Variável | Valor |
|---|---|---|
| todos | `PORT` | porta do serviço (o healthcheck da Railway usa essa porta) |
| prometheus | `COLLECTOR_METRICS_TARGET` | `lhamalog-collector.railway.internal:9464` (nome do serviço do Collector na Railway) |
| prometheus | `PROMETHEUS_RETENTION` | `7d` |
| openobserve | `ZO_ROOT_USER_EMAIL` / `ZO_ROOT_USER_PASSWORD` | **segredo**: `preserve()`, variável selada |
| alloy | `OPENOBSERVE_URL` | `http://openobserve.railway.internal:5080` |
| alloy | `OPENOBSERVE_PLATFORM_ORG` / `OPENOBSERVE_PLATFORM_STREAM` | `default` / `lhamalog_platform` (mesmos defaults na config) |
| alloy | `OPENOBSERVE_AUTH_BASIC` | **segredo**: `preserve()`, variável selada; base64 de `email:passcode de ingestão`, com ou sem o prefixo `Basic ` (o Alloy normaliza) |
| grafana | `OPENOBSERVE_USERNAME` / `OPENOBSERVE_PASSWORD` | **segredo**: `preserve()`, variáveis seladas; login com permissão de pesquisa na org `default` (o passcode de ingestão não pesquisa) |
| grafana | `GF_SECURITY_ADMIN_USER` | `admin` |
| grafana | `GF_SECURITY_ADMIN_PASSWORD` | **segredo**: `preserve()` no IaC. Criar antes como variável (selada) do serviço no dashboard; nunca em arquivo. Sem ela (ou vazia, ou `admin`) o entrypoint `infra/railway/grafana/entrypoint.sh` aborta com `event=grafana.admin_password.missing` e o Grafana não sobe: nada de `admin/admin` gravado no volume |
| grafana | `GF_AUTH_ANONYMOUS_ENABLED` / `GF_USERS_ALLOW_SIGN_UP` | `false` |
| grafana | `GF_SECURITY_COOKIE_SECURE` | `true` (acesso só pelo domínio HTTPS) |
| grafana | `PROMETHEUS_URL` / `OPENOBSERVE_URL` | `http://prometheus.railway.internal:9090` / `http://openobserve.railway.internal:5080` |
| Collector (serviço dele, fora deste IaC) | `MANAGEMENT_ADDRESS` | `::` (o default `127.0.0.1` não é alcançável por outro serviço) |

### Rede privada

- Prometheus, OpenObserve e Alloy **sem** domínio público e sem TCP proxy: só `*.railway.internal`, rede privada (Wireguard) isolada por projeto e ambiente. Nunca gerar domínio para eles.
- O OpenObserve exige login (usuário root e credencial de ingestão em variáveis seladas). A porta de management do Collector (9464, sem autenticação) também nunca recebe domínio público.
- Os serviços escutam em todas as interfaces (IPv4 e IPv6); ambientes antigos da Railway só resolvem `railway.internal` em IPv6.
- O Grafana chega no Prometheus e no OpenObserve pelas URLs privadas acima (datasources provisionados em `infra/railway/grafana/provisioning`, com os dashboards de `infra/local/observability/grafana/dashboards` embutidos na imagem).

### Como o Collector alimentaria o módulo

- **Métricas:** o Prometheus raspa `http://lhamalog-collector.railway.internal:9464/actuator/prometheus` (pull). Basta o Collector rodar com `MANAGEMENT_ADDRESS=::`.
- **Logs:** na Railway não há arquivo de log compartilhado para o Alloy ler. O Alloy recebe logs OTLP/HTTP em `http://alloy.railway.internal:4318/v1/logs`, grava `service` (de `service.name`) e `level`, e manda para o OpenObserve (org `default`, stream `lhamalog_platform`). Falta no Collector um exportador OTLP de logs (ex.: appender OpenTelemetry do Logback) — fica como follow-up, não está nesta mudança. Enquanto isso, os logs do Collector seguem só no log da própria Railway.

### Como implantar depois (com OK do Michel)

1. Instalar a CLI da Railway (5.42.1 ou mais nova) e `npm install` em `.railway/` (pacote `railway` 3.17.0).
2. `railway link` no projeto/ambiente certo.
3. Criar as variáveis seladas: `GF_SECURITY_ADMIN_PASSWORD`, `OPENOBSERVE_USERNAME` e `OPENOBSERVE_PASSWORD` no `grafana`; `OPENOBSERVE_AUTH_BASIC` no `alloy`; `ZO_ROOT_USER_EMAIL` e `ZO_ROOT_USER_PASSWORD` no `openobserve` (dashboard).
4. `railway config plan` e revisar o plano (4 serviços, 4 volumes, nada fora do grupo `Observability`).
5. `railway config apply` (sem `--yes`).
6. Gerar domínio público **só** para o `grafana` (porta 3000) e configurar `MANAGEMENT_ADDRESS=::` no serviço do Collector.

### Custo

Nada foi implantado, então não há custo agora. Na Railway a cobrança é por uso (CPU, memória, volume e tráfego de saída; tráfego na rede privada não conta como egress). São quatro serviços sempre ligados e 3,75 GB de volume: estimar no painel de uso da Railway antes de aplicar.

### Validação local feita (16/11/2026)

`tsc` do `railway.ts` com o pacote `railway` 3.17.0 e avaliação do programa (Development e production); `docker build` das quatro imagens; containers numa rede descartável: Prometheus, Loki, Alloy e Grafana prontos, Grafana 401 sem credencial, datasources Prometheus e Loki com health OK, dashboard provisionado, log OTLP enviado ao Alloy chegando no Loki. (Validação anterior à troca do Loki pelo OpenObserve; a troca foi validada só no stack local.) Containers e imagens removidos.
