# Research — 008-collector-metrics

## R1 — Prometheus registry + OTLP coexist

**Decision**: Adicionar `io.micrometer:micrometer-registry-prometheus` (versão do BOM Spring Boot 4.1.1). Manter export OTLP.

**Rationale**: Scrape local/Grafana; OTLP já alimenta o collector de telemetria do compose principal.

**Alternatives**: Só OTLP → rejeitado (Q3 exige Prometheus+Grafana). Só Pushgateway → complexidade sem ganho.

## R2 — Management port

**Decision**: `management.server.port=${MANAGEMENT_PORT:9464}` (não 8081 — Core local). `management.server.address=${MANAGEMENT_ADDRESS:}` (vazio = all interfaces local; prod documentar 127.0.0.1 / rede interna). Expor `health,info,prometheus`.

**Rationale**: Isola scrape; evita conflito com Core; Prometheus no Docker usa `host.docker.internal:9464`.

## R3 — SecurityFilterChain

**Decision**: Duas chains: (1) `@Order(0)` `securityMatcher(EndpointRequest.to(HealthEndpoint, InfoEndpoint, PrometheusScrapeEndpoint))` → `permitAll`; (2) catch-all preservando default autenticado. Sem isso, qualquer `SecurityFilterChain` remove o default e quebraria auth da app.

**Rationale**: `/actuator/metrics` retornou 401 no E2E LLT-183 com security default; precisamos liberar só scrape.

## R4 — MeterFilter de tags

**Decision**: Remover tags `tenantId`, `tenant_id`, `tenant`, `productId`, `product_id`, `token`. Common tag `application=${spring.application.name}`.

## R5 — Onde instrumentar aceite e latência

**Decision**:
- **Latency**: wrappers nos receivers (`OtlpHttpServer` / `OtlpGrpcServer`) com `protocol=http|grpc`, `signal=logs|traces`, `outcome` derivado de `IngestionDecision` (accepted = Accepted|PartiallyAccepted|EmptySuccess; refused = auth/rate/size/quota/suspended/invalid; failed = DestinationUnavailable|CatalogUnavailable + exceptions).
- **Accepted counters**: após `sink.deliver` sucesso em `BatchIngestion` (ou callback em observability), bytes via `CanonicalOtlpMeasurement.serializedBytes()` — mesma medida de usage.

**Alternatives**: Só Export*Service perde a tag `protocol` sem thread-local; Publishing*Sink não vê protocol facilmente.

## R6 — Histogram buckets

**Decision**: `{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30}` segundos via `Timer.builder(...).publishPercentileHistogram()` / `serviceLevelObjectives`.

## R7 — Queue/DLQ gauges

**Decision**: `@Scheduled` refresher (`lhamalog.metrics.queue-size-refresh=${LHAMALOG_METRICS_QUEUE_SIZE_REFRESH:15s}`) usa `StringRedisTemplate.opsForStream().size(key)`. Keys: `StreamNames.stream/dlq("logs"|"traces")`. Só quando Redis/messaging disponível (o Collector sempre tem `StringRedisTemplate`; **sem** `@ConditionalOnBean` no componente escaneado, porque ele é avaliado antes da autoconfiguração do Redis e descartava os gauges — achado da verificação local de 16/11). Falha → keep last / NaN; WARN `event=metrics.queue_size.refresh_failed` rate-limited (≥60s entre logs por signal).

## R8 — Log shipping: Alloy + arquivo

**Decision**: Perfil local `logging.file.name=${LHAMALOG_LOG_FILE:logs/lhamalog-collector.log}`; Alloy tails `../../logs` RO; regex princípio VI; labels `service`,`level`; `cid`/`event` como structured metadata; multiline por timestamp ISO. `logs/` no `.gitignore`.

**Why not OTLP logs / Loki appender**: sem nova dependência runtime, linha exatamente preservada, funciona para qualquer processo Java local.

## R9 — Imagens Docker (tags explícitas)

| Service | Image tag |
|---------|-----------|
| Prometheus | `prom/prometheus:v3.5.0` |
| Grafana | `grafana/grafana:12.2.0` |
| Loki | `grafana/loki:3.5.3` |
| Alloy | `grafana/alloy:v1.10.2` |

Se pull falhar no live check, ajustar para a última patch estável da mesma major.

## R10 — http.server.requests

**Decision**: Documentar que cobre sobretudo MVC/actuator; latência OTLP = `lhamalog.ingestion.latency`. Dashboard ainda mostra painéis `http_server_requests_*` para tráfego management.
