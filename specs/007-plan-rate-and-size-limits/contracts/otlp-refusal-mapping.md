# Contrato — Mapeamento de recusas OTLP (LLT-183 + spec006)

**Status**: normativo para implementação desta feature. Complementa `../006-ingest-quota-reservation/contracts/otlp-quota-rejection.md`.

## Regra comum

Recusas desta feature e da spec006 descartam o lote temporário, não publicam no stream, não gravam no OpenObserve e não incluem saldo, plano, IDs internos nem segredos na resposta ao exporter.

Ordem de avaliação: **autenticação → taxa → tamanho → reserva (spec006)**.

## Tabela de mapeamento

| Condição | HTTP protobuf/JSON | gRPC | Retry-After (HTTP) / RetryInfo (gRPC) | Observabilidade |
|---|---|---|---|---|
| Taxa excedida (≥ 20 req/s na janela) | `429` | `RESOURCE_EXHAUSTED` | `lhamalog.rate-limit.retry-after` default **5** s | `reason=rate_limit` |
| Tenant bloqueado (5 violações / 60 s) | `429` | `RESOURCE_EXHAUSTED` | `lhamalog.rate-limit.block-retry-after` default **300** s | `reason=rate_limit_blocked` |
| Lote > 5 MB (e ≤ teto global) | `413` | `RESOURCE_EXHAUSTED` | não obrigatório | `reason=batch_too_large` |
| Lote > teto global 64 MB | `413` (existente) | `RESOURCE_EXHAUSTED` (Netty/existente) | — | existente TOO_LARGE |
| Saldo insuficiente (spec006) | `429` | `RESOURCE_EXHAUSTED` | **120** | `reason=insufficient_balance` |
| Assinatura SUSPENSA | `403` | `PERMISSION_DENIED` | — | `reason=subscription_suspended` |
| Sem plano / Core indisponível / timeout / 5xx (tentativas 1..5) | `503` | `UNAVAILABLE` | `lhamalog.quota.unavailable-retry-after` default **300** | `reason=quota_unavailable` |
| Orçamento Core indisponível esgotado (tentativa 6+) | `500` | `INTERNAL` | **sem** Retry-After / RetryInfo | `reason=quota_unavailable_exhausted` |
| Redis rate-limit indisponível | conforme política Q13 (default provisório: **não** recusar por taxa; WARN + métrica) [A CONFIRMAR] | idem | — | `reason=rate_limit_store_unavailable` |
| Redis contador Core-unavailable indisponível | `503` | `UNAVAILABLE` | **300** (sem orçamento) | `reason=quota_unavailable_attempt_store_unavailable` + WARN |

**gRPC:** a orientação de throttle usa `google.rpc.RetryInfo` em `grpc-status-details-bin` (não o trailer ASCII `retry-after`). **HTTP:** header `Retry-After`; no corpo protobuf `google.rpc.Status`, o mesmo atraso também vai em `RetryInfo` nos detalhes.

## Distinção 429 taxa vs 429 saldo

- Mesmo status HTTP/gRPC.
- Retry-After **diferente** (taxa = 5 ou 300 se bloqueado; saldo = 120).
- Labels/métricas/logs **obrigatoriamente** distintos (`rate_limit` / `rate_limit_blocked` vs `insufficient_balance`).

## Compatibilidade

Comprovar equivalência de decisão (aceitar/recusar + orientação de retry coerente) em OTLP HTTP protobuf, HTTP JSON e gRPC.
