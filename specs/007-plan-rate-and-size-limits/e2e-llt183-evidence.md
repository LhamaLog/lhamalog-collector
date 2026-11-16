# Evidências E2E LLT-183 / spec 007 — taxa, tamanho, saldo, excedente, suspensão, inadimplência, Core fora e correlation id

Data: 2026-11-16 08:44:46 (America/Sao_Paulo)

Nenhum token, senha ou segredo registrado. Script: `%TEMP%\llt183-e2e\run-e2e.ps1`; envio OTLP: `%TEMP%\llt183-e2e\send.py`.

## Preparação
- token de serviço gerado por execução, só no ambiente do processo: length=64 sha256=812298bfe4e14613eaf9ea5c49f6301514ba12f7e2c1ad6cb094a40117ff2b87
- infra: docker compose -p llt183-e2e (infra/local/compose.yaml do worktree: Mongo, Redis, OpenObserve v1.1.2, otel-collector) + Mongo descartável do Core (porta 27019); jars empacotados do HEAD 908dfe1
- seed Core: 1 GB ATIVA (p-cal, p-rate/tenant-rate, p-size/tenant-size, p-down); 1 byte sem excedente (p-zero) e com overageEnabled=true (p-over); SUSPENSA (p-susp); INADIMPLENTE há 20 dias (p-inad-old), há 1 dia (p-inad-new) e sem delinquentSince (p-inad-null); p-noplan sem assinatura. Cada produto em tenant próprio.
- Core (8081) e Collector (8080, OTLP HTTP 4328 / gRPC 4327, quota.enabled=true, usage.enabled=true, actuator metrics exposto só no E2E) em execução

## Aceite com correlation id ponta a ponta (p-cal)
- HTTP JSON com X-Correlation-Id: status=200 retry_after=None cid_echo=e2e-llt183-cid-0001
- gRPC com x-correlation-id: grpc=OK
- reservas Core p-cal: 2
- linhas com cid=e2e-llt183-cid-0001: Collector=0, Core=0

## Taxa: rajada de 40 requisições simultâneas (tenant-rate, limite 20/s, bloqueio na 5ª violação)
- rajada HTTP JSON: burst=40 status=200 retry_after=None x20; status=429 retry_after=300 msg=ingestion rate limited, retry later x16; status=429 retry_after=5 msg=ingestion rate limited, retry later x4
- reservas Core p-rate: antes=0 depois=20 (só as aceitas reservam)
- requisição seguinte (tenant bloqueado) HTTP JSON: status=429 retry_after=300 msg=ingestion rate limited, retry later
- requisição seguinte gRPC: grpc=RESOURCE_EXHAUSTED retry_after=300
- chaves Redis do rate limit de tenant-rate: lhamalog:ratelimit:block:tenant-rate lhamalog:ratelimit:violations:tenant-rate lhamalog:ratelimit:tenant-rate:1794829447
- ordem taxa → tamanho: lote de 6 MB no tenant bloqueado: status=429 retry_after=300 msg=ingestion rate limited, retry later

## Tamanho: lote acima de 5 MB (tenant-size)
- HTTP JSON 6 MB: status=413 retry_after=None msg=OTLP batch exceeds the application size limit
- HTTP protobuf 6 MB: status=413 retry_after=None rpc_code=8
- gRPC 6 MB: grpc=RESOURCE_EXHAUSTED retry_after=None
- reservas Core p-size após recusas: 0 (esperado 0)
- HTTP JSON 4 MB (abaixo do limite): status=200 retry_after=None
- reservas Core p-size: 1

## Saldo e excedente
- p-zero (1 byte, overageEnabled=false) HTTP JSON: status=429 retry_after=120 msg=ingestion quota exhausted, retry later
- p-over (1 byte, overageEnabled=true) HTTP JSON: status=200 retry_after=None cid_echo=e2e-llt183-cid-over
- log do Core: `[cid=e2e-llt183-cid-over] [http-nio-8081-exec-2] b.c.l.c.u.a.QuotaReservationService.recordOverageAuthorized - event=quota.overage.authorized | Reservation authorized above the plan allowance | tenantId=tenant-p-over productId=p-over reservationId=quota:9351fe48f3b2cdef2a8c9e62970950f487d4aaafe4b5d634d15e6cd7d502fcf6 overageBytes=76`
- métrica Core lhamalog.quota.overage: n/a

## Assinatura SUSPENSA e INADIMPLENTE
- p-susp HTTP JSON: status=403 retry_after=None msg=subscription suspended; protobuf: status=403 retry_after=None rpc_code=7; gRPC: grpc=PERMISSION_DENIED retry_after=None
- p-inad-old (INADIMPLENTE há 20 dias) HTTP JSON: status=403 retry_after=None msg=subscription suspended; gRPC: grpc=PERMISSION_DENIED retry_after=None
- p-inad-new (INADIMPLENTE há 1 dia) HTTP JSON: status=200 retry_after=None
- p-inad-null (sem delinquentSince) HTTP JSON: status=200 retry_after=None
- linhas event=subscription.delinquent.grace_expired no Core: 2
- linhas event=subscription.delinquent.in_grace no Core: 1
- linhas event=subscription.delinquent.missing_since no Core: 1

## Sem plano (p-noplan)
- HTTP JSON: status=503 retry_after=300 msg=ingestion quota unavailable, retry later

## Core indisponível: orçamento de 5 tentativas por lote (p-down)
- Core parado
- tentativa 1 do mesmo lote (HTTP JSON): status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- tentativa 2 do mesmo lote (HTTP JSON): status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- tentativa 3 do mesmo lote (HTTP JSON): status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- tentativa 4 do mesmo lote (HTTP JSON): status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- tentativa 5 do mesmo lote (HTTP JSON): status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- tentativa 6 do mesmo lote (HTTP JSON): status=500 retry_after=None msg=core unavailable: retry budget exhausted, batch dropped
- 7ª tentativa (HTTP JSON, mesmo lote): status=500 retry_after=None msg=core unavailable: retry budget exhausted, batch dropped
- lote B, tentativa 1 com Core fora: status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- lote B, tentativa 2 com Core fora: status=503 retry_after=300 msg=ingestion quota unavailable, retry later
- chaves lhamalog:quota:core-unavailable:* antes de o Core voltar: lhamalog:quota:core-unavailable:quota:a6efddcf7cbb3cb41eb73e745bc49e62f8d6e520373e330f9f4202e6ae74ab32 lhamalog:quota:core-unavailable:quota:50cc1e93409114be945f5c60a8635d2d7f35325a16b81826fde253de93093250 lhamalog:quota:core-unavailable:quota:fff60889a6a5254c92b1a8b44e37622ce402e8109601427a8defdc5204ba3bea
- Core reiniciado
- lote B, tentativa 3 com Core de volta: status=200 retry_after=None
- quantidade de chaves core-unavailable depois: 2

## Persistência e fila
- XLEN lhamalog:stream:logs: 27; XLEN DLQ: 0
- OpenObserve org=lhamalog stream=logs registros na última hora: 27

## Métricas do Collector (actuator, só no E2E)
- `GET /actuator/metrics` no Collector respondeu **HTTP 401**: o Spring Security padrão protege o actuator além de `health`/`info`, então os contadores não puderam ser lidos por scrape (os valores 0 listados pelo script eram falha de leitura, não ausência de recusas).
- As recusas aparecem nos logs `event=ingestion.refused | ... | reason=...` (com `cid` por requisição) e as métricas `lhamalog.ingestion.refused{reason}` são exportadas por OTLP (`management.otlp.metrics.export.url`; o otel-collector local só tem exporter debug).
- Leitura das métricas por dashboard fica para a LLT-187.

## Redis fora: rate limiter fail-open (Q13)
- p-cal HTTP JSON com Redis parado: status=503 retry_after=120 msg=destination unavailable, retry later
- linhas event=ingestion.rate_limit.store_unavailable no Collector: 2; recusas por taxa nessa requisição: nenhuma (a recusa, se houver, vem do passo seguinte)

## Segurança dos logs
- ocorrências em claro nos logs: token de serviço=0, tokens de produto=0
- teardown: Mongo do Core removido
- teardown: docker compose -p llt183-e2e down -v (só volumes deste projeto E2E)
