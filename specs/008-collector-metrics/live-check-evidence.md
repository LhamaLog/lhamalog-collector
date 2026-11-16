# Evidência da verificação local LLT-187 / spec 008 — Prometheus, Grafana e Loki

Executado em 16/11/2026 18:01 (BRT) no Avell, com o jar do working tree (sem commit). Segredos gerados por execução só no ambiente do processo.

## Preparação
- senhas e token gerados por execução, só no ambiente do processo (token length=32)
- infra: compose.yaml do worktree (-p llt187-live: Mongo, Redis, OpenObserve, otel-collector) + compose.observability.yaml (-p lhamalog-observability: alloy=grafana/alloy:v1.10.2, grafana=grafana/grafana:12.2.0, loki=grafana/loki:3.5.3, prometheus=prom/prometheus:v3.5.0)
- Collector (jar do working tree) em execução: perfil local, management 9464 (bind default), OTLP HTTP 4328 / gRPC 4327, log em logs/lhamalog-collector.log

## Exposição de rede e acesso (review B2)
- porta publicada: alloy 
- porta publicada: grafana 127.0.0.1:3000->3000/tcp
- porta publicada: loki 127.0.0.1:3100->3100/tcp
- porta publicada: prometheus 127.0.0.1:9090->9090/tcp
- management 9464 escutando em: 127.0.0.1 (MANAGEMENT_ADDRESS não definido = default)
- Grafana sem credencial GET http://127.0.0.1:3000/api/dashboards/uid/lhamalog-collector -> 401
- Grafana sem credencial GET http://127.0.0.1:3000/api/search -> 401
- Grafana sem credencial GET http://127.0.0.1:3000/api/datasources -> 401
- conexão TCP pelo IP da LAN (<lan>):9090 -> recusada
- conexão TCP pelo IP da LAN (<lan>):3100 -> recusada
- conexão TCP pelo IP da LAN (<lan>):3000 -> recusada
- conexão TCP pelo IP da LAN (<lan>):9464 -> recusada

## Endpoint de scrape (segurança)
- GET http://127.0.0.1:9464/actuator/prometheus -> 200
- GET http://127.0.0.1:9464/actuator/health -> 200
- GET http://127.0.0.1:9464/actuator/env -> 401
- GET http://127.0.0.1:9464/actuator/metrics -> 401
- GET http://127.0.0.1:8080/actuator/prometheus -> 401
- GET http://127.0.0.1:8080/qualquer -> 401

## Tráfego OTLP
- HTTP JSON aceito (cid e2e-llt187-cid-ok): status=200 retry_after=None cid_echo=e2e-llt187-cid-ok
- HTTP protobuf aceito: status=200 retry_after=None
- gRPC aceito: grpc=OK
- HTTP JSON 6 MB (recusa por tamanho, cid e2e-llt187-cid-0001): status=413 retry_after=None cid_echo=e2e-llt187-cid-0001 msg=OTLP batch exceeds the application size limit
- token inválido: status=401 retry_after=None msg=missing or invalid bearer token
- scrape: `lhamalog_dlq_size{application="lhamalog-collector",signal="logs"} 0.0`
- scrape: `lhamalog_dlq_size{application="lhamalog-collector",signal="traces"} 0.0`
- scrape: `lhamalog_ingestion_accepted_bytes_total{application="lhamalog-collector",protocol="grpc",signal="logs"} 104.0`
- scrape: `lhamalog_ingestion_accepted_bytes_total{application="lhamalog-collector",protocol="http",signal="logs"} 154.0`
- scrape: `lhamalog_ingestion_accepted_records_total{application="lhamalog-collector",protocol="grpc",signal="logs"} 3.0`
- scrape: `lhamalog_ingestion_accepted_records_total{application="lhamalog-collector",protocol="http",signal="logs"} 3.0`
- scrape: `lhamalog_ingestion_latency_seconds_count{application="lhamalog-collector",outcome="accepted",protocol="grpc",signal="logs"} 1`
- scrape: `lhamalog_ingestion_latency_seconds_count{application="lhamalog-collector",outcome="accepted",protocol="http",signal="logs"} 2`
- scrape: `lhamalog_ingestion_latency_seconds_count{application="lhamalog-collector",outcome="refused",protocol="http",signal="logs"} 2`
- scrape: `lhamalog_ingestion_refused_total{application="lhamalog-collector",reason="batch_too_large"} 1.0`
- scrape: `lhamalog_queue_size{application="lhamalog-collector",signal="logs"} 1.0`
- scrape: `lhamalog_queue_size{application="lhamalog-collector",signal="traces"} 0.0`
- linhas do scrape com label tenant/product/token: 0

## Prometheus (via Grafana datasource proxy)
- alvo lhamalog-collector http://host.docker.internal:9464/actuator/prometheus health=up lastError=''
- PromQL `sum by (signal, protocol) (lhamalog_ingestion_accepted_records_total)`: protocol=grpc,signal=logs => 3; protocol=http,signal=logs => 3
- PromQL `sum by (signal, protocol) (lhamalog_ingestion_accepted_bytes_total)`: protocol=grpc,signal=logs => 104; protocol=http,signal=logs => 154
- PromQL `sum by (reason) (lhamalog_ingestion_refused_total)`: reason=batch_too_large => 1
- PromQL `sum by (signal, protocol, outcome) (lhamalog_ingestion_latency_seconds_count)`: outcome=accepted,protocol=grpc,signal=logs => 1; outcome=accepted,protocol=http,signal=logs => 2; outcome=refused,protocol=http,signal=logs => 2
- PromQL `lhamalog_queue_size`: signal=logs => 3; signal=traces => 0
- PromQL `lhamalog_dlq_size`: signal=logs => 0; signal=traces => 0
- PromQL `sum(jvm_memory_used_bytes{area="heap"})`:  => 72923760
- PromQL `sum by (uri) (http_server_requests_seconds_count)`: uri=UNKNOWN => 1; uri=/** => 1

## Grafana (datasources e dashboard provisionados)
- health: ok / versão 12.2.0
- datasources: Loki(uid=loki,type=loki), Prometheus(uid=prometheus,type=prometheus)
- dashboard 'LhamaLog Collector' uid=lhamalog-collector painéis=16 variáveis=cid,level url=http://localhost:3000/d/lhamalog-collector/lhamalog-collector

## Loki (via Grafana datasource proxy)
- labels Loki: filename, level, service, service_name
- valores de level: DEBUG, INFO
- linha no Loki (stream cid=e2e-llt187-cid-0001,detected_level=INFO,filename=/var/log/lhamalog/lhamalog-collector.log,level=INFO,logger=b.c.l.c.i.o.a.IngestionObservability.refused,service=lhamalog-collector,service_name=lhamalog-collector): `2026-11-16T18:00:30.623-03:00  INFO [lhamalog-collector] [cid=e2e-llt187-cid-0001] [virtual-123] b.c.l.c.i.o.a.IngestionObservability.refused - event=ingestion.refused | OTLP ingestion refused | reason=batch_too_large`
- LogQL `{service="lhamalog-collector"} |= "cid=e2e-llt187-cid-0001"`: 1 linha(s)
- total de linhas do Collector no Loki (15 min): 33

## Segurança dos logs
- token do produto em claro nos logs: 0; senha do Redis: 0
- teardown: lhamalog-observability down -v
- teardown: llt187-live down -v
