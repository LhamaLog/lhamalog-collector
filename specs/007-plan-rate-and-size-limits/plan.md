# Plano de Implementação: Limites de taxa e tamanho de lote (LLT-183)

**Branch**: `feature/LLT-183-plan-rate-size-limits` / Spec Kit `007-plan-rate-and-size-limits` | **Data**: 2026-11-16 | **Spec**: [spec.md](spec.md)

**Entrada**: Especificação em `specs/007-plan-rate-and-size-limits/spec.md` (clarify parcial 16/11/2026).

## Resumo

Introduzir no Collector: (1) rate limit de **20 req/s por tenant** (janela 1 s, Redis; Retry-After 5 s; 5 violações/60 s → bloqueio 5 min); (2) teto de aplicação de lote **5 MB** além do teto técnico 64 MB; ordem **taxa → tamanho → reserva (spec006)**; (3) mapear assinatura **SUSPENSA** para 403 / PERMISSION_DENIED e **`overageEnabled`** no Core; (4) Core/sem plano = 503 + Retry-After **300 s**, até 5 tentativas/lote; 6ª → 500/INTERNAL (saldo insuficiente permanece 120 s).

Itens ainda **[A CONFIRMAR]**: Q13 (Redis-down rate limiter) e Q14 (INADIMPLENTE). D4/D5 resolvidos em 16/11/2026 (2).

## Contexto Técnico

**Language/Version**: Java 25 (Collector e Core).

**Primary Dependencies**: Spring Boot 4.1.1, Spring Data Redis (Lettuce), Spring Data MongoDB (Core), OTLP HTTP (JDK HttpServer) / gRPC Netty, componentes existentes de quota (spec006).

**Storage**: Redis (contadores de taxa + Streams existentes); MongoDB no Core (`PlanSubscription` estendido).

**Testing**: JUnit 5, AssertJ, Mockito, Testcontainers; testes de contrato HTTP/gRPC e Core reserve; testes unitários de Lua/rate e tamanho.

**Target Platform**: serviços Linux em contêiner; desenvolvimento Windows.

**Project Type**: dois serviços Java — Collector (OTLP) e Core (API interna de uso/quota).

**Performance Goals**: checagem de taxa após auth e antes de ler/decodificar o body (HTTP); sem SLO numérico adicional além do limite de 20 req/s.

**Constraints**: sem segredos em artefatos; docs pt-BR / código EN; não reimplementar reserva/refund/SALDO_BAIXO; Q13 provisório marcado; INADIMPLENTE sem comportamento de negócio inventado.

**Scale/Scope**: MVP nó único (ADR_164); Redis compartilhado antecipa multi-instância do Collector.

## Verificação da Constituição

| Princípio | Situação antes do design | Situação após o design |
|---|---|---|
| I. SpecKit em toda feature | Spec + clarify atualizados; plan/tasks nesta fase | Conforme |
| II. IA com revisão e domínio do desenvolvedor | Decisões Michel + técnicas do dev registradas | Conforme |
| III. Testes antes do merge | Tasks exigirão testes antes da implementação | Conforme |
| IV. Inglês no código, português na documentação | Artefatos humanos em pt-BR | Conforme |
| V. Segredos fora do código | Só nomes de propriedades/env; sem valores | Conforme |

**Gates**: sem violação. Itens [A CONFIRMAR] não bloqueiam o design; bloqueiam só a confirmação do valor final.

## Fluxo Planejado (Collector)

1. Concorrência HTTP (existente) → path/método → autenticação Bearer (`BatchIngestion` / token store) → **tenant conhecido**.
2. **Rate check** (novo): Lua atômica (block → janela INCR → violações → block); > 20 → `RateLimited` Retry-After **5 s**; 5ª violação/60 s → bloqueio Retry-After **300 s**. **Antes** de `OtlpPayload.read()` / uso do body.
3. **Size check** de aplicação (novo, 5 MB): mede **payload descomprimido/decodificado** (igual ao teto global). HTTP: após taxa, durante/após leitura do body, antes de parse de negócio/reserva. gRPC: Netty já aplicou 64 MB; após auth+taxa, valida tamanho da mensagem já decodificada (serialized size / size conhecido) ≤ 5 MB. Recusa → 413 / RESOURCE_EXHAUSTED. Teto 64 MB permanece.
4. Fluxo existente: assess → **reserva spec006** (paralela à sanitização) → confirm → sinks.
5. Core `reserve`: se `status=SUSPENSA` → outcome `SUSPENDED` HTTP 403 → Collector `Suspended` → 403 / PERMISSION_DENIED. Se `overageEnabled` e saldo insuficiente → autoriza e registra excedente (sem cobrança). `INADIMPLENTE`: campo existe; comportamento de ingestão **[NEEDS CLARIFICATION Q14]** — até confirmação, tratar como ATIVA com log/métrica de status (não inventar bloqueio).
6. Core indisponível / NO_PLAN: contador Redis por `reservationId` determinístico; tentativas 1..5 → 503 + Retry-After **300 s**; 6+ → 500/INTERNAL. Saldo insuficiente: **120 s** inalterado.

## Seção Core (arquivos concretos)

| Área | Arquivos |
|------|----------|
| Modelo | `lhamalog-core-llt183/src/main/java/br/com/lhamalog/core/usage/application/PlanSubscription.java` — campos `status`, `overageEnabled` |
| Enum | novo `SubscriptionStatus` (ATIVA, SUSPENSA, INADIMPLENTE) |
| Serviço | `QuotaReservationService.java` — ramo SUSPENSA; ramo overage (autorizar + registrar bytes excedente) |
| API | `UsageReservationsController.java` — mapear SUSPENDED → **403**; response `outcome` |
| Persistência | `MongoPlanSubscriptionRepository.java`, índices se necessário |
| Testes | `QuotaReservationServiceTest.java`, `UsageReservationsControllerContractTest.java`, helpers in-memory |

## Seção Collector (arquivos concretos)

| Área | Arquivos |
|------|----------|
| Pipeline | `BatchIngestion.java` — ordem taxa → read/size → quota |
| Rate | novo pacote `.../ingestion/otlp/ratelimit/` (port + Redis Lua INCR/EXPIRE) |
| Size | `OtlpPayload.java` / propriedades `OtlpReceiverProperties` (`max-batch-size`) |
| Decisões | `IngestionDecision` / `QuotaGate.Outcome` — `RATE_LIMITED`, `BATCH_TOO_LARGE`, `SUSPENDED` |
| Cliente Core | `HttpQuotaReservationClient.java` — mapear 403 → SUSPENDED; overage outcomes se houver |
| Gate | `CanonicalQuotaGate.java`, `QuotaGate.java` |
| HTTP/gRPC | `OtlpHttpStatusMapper.java`, `OtlpGrpcServer.java` — 429 taxa vs saldo; 413 tamanho; 403 suspensão; 503 Retry-After 300 |
| Props | `QuotaProperties` / novas `RateLimitProperties`; Retry-After Core 300 |
| Redis | `StringRedisTemplate` existente |
| Testes | espelhar sob `src/test/java/.../ingestion/otlp/`, `.../ratelimit/`, `.../quota/` |

## Contratos e Integrações

- [Mapeamento OTLP de recusas](contracts/otlp-refusal-mapping.md)
- [API de reserva Core (extensão)](contracts/usage-reservations-extension.md)
- [Modelo de dados](data-model.md)
- [Research](research.md)
- [Quickstart](quickstart.md)

## Estratégia de Testes

- Unitários: Lua/rate window, size 5 MB vs 64 MB, ordem no `BatchIngestion`, fail-open Redis provisório.
- Contrato Core: 403 SUSPENDED; overage authorize; defaults ATIVA/false.
- OTLP HTTP protobuf/JSON + gRPC: taxa, tamanho, suspensão, saldo (120), Core fora (300).
- Integração: taxa esgotada → 0 chamadas reserve; tamanho → 0 reserve; SUSPENSA → 403.

## Estrutura do Projeto

### Documentação

```text
specs/007-plan-rate-and-size-limits/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── clarify-questions.md
├── progress-notes.md
├── analyze-report.md
├── contracts/
│   ├── otlp-refusal-mapping.md
│   └── usage-reservations-extension.md
└── tasks.md
```

### Código previsto

```text
lhamalog-collector/
├── src/main/java/br/com/lhamalog/collector/
│   ├── config/OtlpReceiverProperties.java          # max-batch-size
│   ├── config/RateLimitProperties.java             # novo
│   └── ingestion/otlp/
│       ├── application/BatchIngestion.java
│       ├── application/OtlpPayload.java
│       ├── application/QuotaGate.java
│       ├── application/IngestionDecision.java      # ou equivalente
│       ├── ratelimit/                              # novo
│       ├── http/OtlpHttpStatusMapper.java
│       ├── grpc/OtlpGrpcServer.java
│       └── quota/HttpQuotaReservationClient.java
└── src/test/java/... (espelho)

lhamalog-core/
├── src/main/java/br/com/lhamalog/core/usage/
│   ├── application/PlanSubscription.java
│   ├── application/SubscriptionStatus.java         # novo
│   ├── application/QuotaReservationService.java
│   └── api/UsageReservationsController.java
└── src/test/java/... (espelho)
```

**Structure Decision**: estender os dois repositórios existentes (Collector + Core), no mesmo padrão da spec006; tasks.md único no Collector com tags **Collector** / **Core**.

## Complexity Tracking

> Sem violações de constituição a justificar.
