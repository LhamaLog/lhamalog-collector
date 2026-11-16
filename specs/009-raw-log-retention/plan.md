# Implementation Plan: Retenção de logs brutos por plano

**Branch**: `feature/LLT-185-raw-log-retention` (`009-raw-log-retention`) | **Date**: 2026-11-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-raw-log-retention/spec.md`

## Summary

Isolar telemetria bruta em streams OpenObserve **por tenant** e aplicar `data_retention` conforme `retentionDays` da resposta de reserva do Core (ADR_131 / ADR_168). Collector: naming determinístico, ingest no stream do tenant, cache Caffeine + PUT settings async, referência Redis atualizada. Core (paralelo): serializar `retentionDays`. Sem migração automática do stream compartilhado.

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.1.1, Caffeine, Micrometer, java.net.http.HttpClient, Testcontainers  
**Storage**: OpenObserve v1.1.2 (streams), Redis Streams (referências), Mongo (tokens/outbox — inalterado)  
**Testing**: JUnit 5, AssertJ, Testcontainers OO, HttpServer stub  
**Target Platform**: Linux server / Docker compose local  
**Project Type**: backend service (Collector)  
**Performance Goals**: settings fora do caminho crítico; cache hit evita PUT  
**Constraints**: sem git write neste agente; sem ler `.env`; logs princípio VI; sem segredos  
**Scale/Scope**: N tenants MVP; cache ~10k entradas; TTL 1 h

## Constitution Check

| Princípio | Status |
|-----------|--------|
| I SpecKit | PASS — specify→clarify→plan→tasks→implement |
| II IA + revisão | PASS — decisões em research/plan |
| III Testes | PASS — unit + integration + `mvnw verify` |
| IV EN código / pt-BR docs | PASS |
| V Segredos | PASS — credenciais OO só env/test random |
| VI Logs event= | PASS — WARN retention + métrica alinhada |

Post-design: sem violação; async settings + correlation id alinhado ao VI.

## Project Structure

### Documentation (this feature)

```text
specs/009-raw-log-retention/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── progress-notes.md
├── analyze-report.md
├── contracts/
│   ├── usage-reservations-retention.md
│   ├── tenant-stream-naming.md
│   ├── finder-openobserve-ref.md
│   └── openobserve-stream-settings.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (touched)

```text
src/main/java/br/com/lhamalog/collector/
├── ingestion/otlp/sink/
│   ├── TenantStreamNames.java          (novo)
│   ├── OpenObserveClient.java          (+ putStreamRetention)
│   ├── OpenObserveLogsSink.java        (stream por tenant + retention)
│   ├── OpenObserveTracesSink.java
│   ├── StreamRetentionService.java     (novo: cache/backoff/async)
│   └── …
├── quota/
│   ├── QuotaReservationClient.java     (ReservationResult + retentionDays)
│   ├── HttpQuotaReservationClient.java
│   ├── CanonicalQuotaGate.java
│   └── RetentionCorrelation.java       (novo ThreadLocal)
├── ingestion/otlp/application/
│   ├── QuotaGate.java                  (Reservation + retentionDays)
│   └── BatchIngestion.java             (set RetentionCorrelation)
└── config/
    ├── OtlpReceiverConfiguration.java
    └── OtlpReceiverProperties.java      (docs: prefix semantics)

src/test/java/... (unit + OpenObserve retention IT)
docs/raw-log-retention-runbook.md
README.md (seção)
```

## Complexity Tracking

Sem violações de constituição. Complexidade extra = ThreadLocal de retenção espelhando `ReservationCorrelation` (já estabelecido) para não alargar a assinatura de todos os sinks.
