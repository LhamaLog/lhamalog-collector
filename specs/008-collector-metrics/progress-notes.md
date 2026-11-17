# Progress notes — 008-collector-metrics (LLT-187)

## 2026-11-16

- Spec directory criada manualmente (`specs/008-collector-metrics/`); sem `create-new-feature.ps1` / sem git write.
- `SPECIFY_FEATURE=008-collector-metrics`; `.specify/feature.json` atualizado.
- Specify: `spec.md` + checklist requirements completo.
- Clarify: decisões Michel Q3/Q4/Loki registradas; R1–R3 com recomendação (não bloqueantes).
- Plan / tasks / analyze concluídos (`analyze-report.md`).
- Implementação: Prometheus registry, management :9464, SecurityFilterChain, MeterFilter, 5 métricas Q4, refresher Redis, compose observability, docs/metricas.md.
- `mvnw.cmd -B verify` verde; `compose.observability.yaml config -q` ok.
- Live check dos containers fica para o parent (não subir compose up nesta sessão).

## Checkpoint 16/11/2026 14:20 (BRT) — verificação local e ajustes do revisor

- `QueueSizeMetricsRefresher`: removido `@ConditionalOnBean(StringRedisTemplate)` (avaliado antes da autoconfiguração do Redis, os gauges não eram registrados); teste de scrape agora exige `lhamalog_queue_size` e `lhamalog_dlq_size`.
- Alloy: removido `stage.output` (a linha no Loki era só a mensagem, sem data/cid/classe.método) e regex com `(?s)` para stack traces.
- Cadeia de segurança da aplicação espelha o padrão do Spring Boot (form login + HTTP Basic, CSRF ligado).
- `management.metrics.distribution.percentiles-histogram.http.server.requests=true` para o painel p95 HTTP.
- Verify às 14:18 (BRT): **305 testes, 0 falhas, 0 erros, 0 ignorados**. Verificação local em `live-check-evidence.md`; teardown feito. Nada commitado.
