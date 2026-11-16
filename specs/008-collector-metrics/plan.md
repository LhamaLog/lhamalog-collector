# Implementation Plan: Expor métricas do Collector (LLT-187)

**Branch**: `feature/LLT-187-collector-metrics` | **Date**: 2026-11-16 | **Spec**: [spec.md](./spec.md)

## Summary

Expor scrape Prometheus na porta de management 9464 (health/info/prometheus permitidos), adicionar exatamente as cinco métricas Q4, filtrar tags proibidas, e provisionar stack local Prometheus + Grafana + Loki/Alloy com dashboard e docs pt-BR — sem tocar Core nem TelemetryIngestedPublisher.

## Technical Context

| Item | Value |
|------|-------|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Metrics | Micrometer + `micrometer-registry-prometheus` (BOM) |
| Existing export | OTLP metrics (mantido) |
| Messaging | Redis Streams (`StringRedisTemplate`, `StreamNames`) |
| Local obs | compose project `lhamalog-observability` |
| Tests | JUnit + SimpleMeterRegistry + MockMvc/WebTestClient/actuator-test |

## Constitution Check

| Principle | Status |
|-----------|--------|
| I SpecKit | OK — specify→clarify→plan→tasks→analyze→implement |
| II IA + review | OK — decisões em research/plan |
| III Testes | OK — unitários + scrape/security |
| IV EN code / pt-BR docs | OK |
| V Segredos | OK — só `.env.example` placeholders; nunca ler `.env` |
| VI Logs | OK — WARN rate-limited e novos eventos no padrão |

## Project Structure (delta)

```text
src/main/java/.../config/          MetricsSecurityConfiguration, MeterFilter config
src/main/java/.../metrics/         QueueSizeMetricsRefresher, MetricsProperties
src/main/java/.../ingestion/...    IngestionObservability extensions; latency/accepted hooks
infra/local/compose.observability.yaml
infra/local/observability/**       prometheus, loki, alloy, grafana provisioning
docs/observability-runbook.md
specs/008-collector-metrics/metricas.md
```

## Phase 0 — Research

Ver [research.md](./research.md). Decisões: registry Prometheus, management port, SecurityFilterChain dupla, MeterFilter, hook de aceite/latência, refresh XLEN, Alloy vs appender, versões de imagens.

## Phase 1 — Design

- [data-model.md](./data-model.md) — métricas e amostras de fila
- [contracts/prometheus-scrape.md](./contracts/prometheus-scrape.md)
- [contracts/dashboard-labels.md](./contracts/dashboard-labels.md)
- [quickstart.md](./quickstart.md)

## Implementation approach

1. Dependência Prometheus + props management port / exposure.
2. SecurityFilterChain ordenada: EndpointRequest health/info/prometheus permitAll; catch-all autentica.
3. MeterFilter + common tag `application`.
4. Estender `IngestionObservability` (ou sibling) para accepted records/bytes + latency histogram.
5. Instrumentar HTTP/gRPC (ou Export*Service) com protocol tag; bytes via `CanonicalOtlpMeasurement`.
6. `QueueSizeMetricsRefresher` @Scheduled + gauges.
7. Compose observability + Grafana dashboard JSON + Alloy config.
8. Makefile, `.env.example`, `.gitignore` logs/, docs, `metricas.md`.
9. Testes + `mvnw verify`.

## Complexity Tracking

Sem violações. Compose separado evita acoplar secrets do stack principal.
