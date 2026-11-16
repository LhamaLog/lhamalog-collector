# Quickstart: verificação LLT-185 / spec 009

## Pré-requisitos

- JDK 25, Docker, `.\mvnw.cmd`
- Infra local: OpenObserve + Redis + Mongo (compose do repo; **não** abrir `.env` neste fluxo automatizado — use vars já exportadas ou defaults de teste)
- Core com `retentionDays` (worktree paralelo) **ou** stub WireMock/HttpServer na reserva

## Testes automatizados

```powershell
$env:SPECIFY_FEATURE = "009-raw-log-retention"
.\mvnw.cmd -B test "-Dtest=TenantStreamNamesTest,StreamRetentionServiceTest,HttpQuotaReservationClientTest,OpenObserveRetentionIntegrationTest"
.\mvnw.cmd -B verify
```

Esperado: verdes; IT sobe OO v1.1.2, ingere dois tenants (7 e 30 dias), GET schema confirma `data_retention`.

## Verificação manual (compose)

1. Subir infra + Collector + Core.
2. Autenticar dois produtos (tenants distintos) com planos FREE (7) e PRO (30).
3. Exportar logs OTLP para cada um.
4. No OO UI/API: listar streams — devem existir `logs_<tenant…>` distintos (não só `logs`).
5. `GET /api/lhamalog/streams/{stream}/schema?type=logs` → `settings.data_retention` = 7 e 30.
6. Redis: entrada em `lhamalog:stream:logs` com `openobserve.stream` = stream do tenant.
7. Simular OO settings down (firewall/proxy): lote ainda HTTP 200; log WARN `event=openobserve.stream_retention.apply_failed`.

## Transição do stream legado

Ver [docs/raw-log-retention-runbook.md](../../docs/raw-log-retention-runbook.md): consultar `logs` + `logs_*` (UNION/duas queries); apagar legado só após retenção global expirar e operação validar.

## Checklist parent (Core + Collector)

- [ ] Core devolve `retentionDays` em AUTHORIZED / AUTHORIZED_UNLIMITED
- [ ] Collector parseia e aplica settings
- [ ] Streams por tenant visíveis
- [ ] Métrica `lhamalog.openobserve.retention.apply` incrementa
- [ ] `mvnw verify` verde nos dois repos
