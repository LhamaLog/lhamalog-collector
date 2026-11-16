# Runbook: retenção de logs brutos por plano (LLT-185)

## O que mudou

- Cada tenant grava em streams OpenObserve próprios: `{prefix}_{tenantSanitizado}` (com hash se necessário). Prefixos: `OPENOBSERVE_LOGS_STREAM` / `OPENOBSERVE_TRACES_STREAM` (default `logs` / `traces`).
- A retenção do stream (`data_retention` em dias) vem de `retentionDays` na resposta de reserva do Core (FREE 7, STARTER 15, PRO 30, BUSINESS/ENTERPRISE 90).
- Retenção enviada pelo Core = maior `retencaoDias` entre as assinaturas ATIVA/INADIMPLENTE do tenant; assinatura antiga sem plano conta como **FREE (7 dias)** (decisão do Michel, 16/11/2026 16:36).
- Sem `retentionDays` na resposta (Core antigo, quota desligada ou tenant sem assinatura considerável): o lote vai ao stream do tenant e o Collector **não** altera o stream; vale a retenção global `ZO_COMPACT_DATA_RETENTION_DAYS`. Esse ramo existe só por compatibilidade.
- A mensagem Redis (`lhamalog:stream:logs` / `traces`) continua no mesmo schema; só o valor de `openobserve.stream` aponta para o stream do tenant.

## Métricas e logs

- Counter: `lhamalog.openobserve.retention.apply{outcome=applied|failed,type=logs|traces}` (sem tag de tenant).
- WARN: `event=openobserve.stream_retention.apply_failed | … | stream=… type=… retentionDays=… status=…`
- Falha de settings **não** recusa o lote já gravado; há backoff ~60 s antes de nova tentativa.

## Transição do stream compartilhado legado (`logs` / `traces`)

1. Dados antigos permanecem no stream legado até a retenção **global** do OpenObserve expirar.
2. Consulta durante a transição: duas queries (legado + `logs_*` do tenant) ou UNION no SQL do OO, conforme a UI/API disponível.
3. Export manual opcional (API de search / UI) antes de apagar.
4. Apagar o stream legado só depois de: (a) retenção global sem hits relevantes; (b) Finder/consultas apontando só para streams por tenant; (c) janela de operação aprovada. **Não há migração automática neste ticket.**

## Verificação rápida

```http
GET /api/lhamalog/streams/{stream}/schema?type=logs
```

Confirmar `settings.data_retention` = N do plano. Ver também `specs/009-raw-log-retention/quickstart.md`.
