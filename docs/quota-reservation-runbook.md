# Runbook: reserva de cota, recusa, refund e SALDO_BAIXO

Procedimentos operacionais da spec006 (`lhamalog.quota.enabled=true`). Não registrar tokens,
corpos OTLP nem saldos em tickets, logs compartilhados ou evidências versionadas.

## Recusa por saldo insuficiente

**Sintomas:** HTTP `429` / gRPC `RESOURCE_EXHAUSTED` com `Retry-After: 120` (ou equivalente);
métrica `lhamalog.quota.insufficient`; ausência de OpenObserve, stream/DLQ e `usage_outbox`.

**Ação:**

1. Confirme no Core o ciclo ativo e o saldo (`reservedBytes` + `consumedBytes` vs franquia).
2. Oriente o exportador a respeitar o retry de 120 s; não force reenvio imediato em massa.
3. Se o saldo estiver correto e ainda houver recusa, verifique se os bytes canônicos do lote
   (spec005) excedem o restante — a recusa é integral, sem aceitação parcial.

## Core indisponível / sem plano / falha de confirm

**Sintomas:** HTTP `503` / gRPC `UNAVAILABLE` com retry 120 s; métrica
`lhamalog.quota.core_unavailable` ou `lhamalog.quota.no_plan`; lote não gravado.

**Ação:**

1. Verifique saúde do Core e conectividade a partir do Collector (`lhamalog.usage.core.url`).
2. Confirme que Collector e Core usam o mesmo token de serviço (trim, sem aspas); ver
   [token-rotation-runbook.md](../specs/005-tenant-product-volume/token-rotation-runbook.md).
3. Para `NO_PLAN`, valide assinatura, `firstPaymentDate` e ciclo pago UTC no Core.
4. Reserva deixada em `RESERVED` por queda entre `reserve` e `confirm` expira em 20 minutos no
   Core; o retry do exportador reutiliza o mesmo `reservationId` determinístico — não há
   reenfileiramento local de uma segunda reserva.

## Refund outbox em `SUSPENDED` (401/403)

**Sintomas:** itens em `quota_refund_outbox` com `state=SUSPENDED` e `lastError` `HTTP_401` /
`HTTP_403`; métrica `lhamalog.quota.refund_suspended`.

**Ação:**

1. Corrija o token de serviço nos dois lados e reinicie os processos.
2. Reabra os itens suspensos no Mongo do Collector (sem colar o token):

```js
db.quota_refund_outbox.updateMany(
  { state: "SUSPENDED", lastError: { $in: ["HTTP_401", "HTTP_403"] } },
  { $set: {
      state: "PENDING",
      lastError: null,
      nextAttemptAt: new Date()
    }
  }
)
```

3. O worker retoma no próximo tick (`lhamalog.quota.refund-worker-interval`). `409` no refund
   é terminal (`DONE`) e não deve ser reaberto.

## `SALDO_BAIXO` com outbox PENDING (Redis desligado)

**Sintomas:** no Core, notificação de limiar 20% ou 5% permanece em outbox `PENDING` porque o
publicador Redis Streams está indisponível ou desabilitado; o débito/`confirm` já ocorreu.

**Ação:**

1. Não reexecute `confirm` para “forçar” o alerta — a chave Mongo do limiar já impede
   reemissão no mesmo ciclo.
2. Restaure o Redis do Core (ou o adaptador de notificação) e deixe o publicador da outbox
   drenar os `PENDING`.
3. Se o Redis permanecer desligado de propósito, trate a outbox como fila durável até a
   reativação; não apague documentos só para “limpar” métricas.

## Referências

- Métricas e logs sanitizados: [usage-measurement.md](usage-measurement.md)
- Contrato de reserva: [`specs/006-ingest-quota-reservation/contracts/reservatokens-api.md`](../specs/006-ingest-quota-reservation/contracts/reservatokens-api.md)
- Quickstart / evidência E2E: [`specs/006-ingest-quota-reservation/quickstart.md`](../specs/006-ingest-quota-reservation/quickstart.md)
