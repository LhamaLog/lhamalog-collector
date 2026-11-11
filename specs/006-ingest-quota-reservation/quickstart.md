# Guia de Validação — Reserva de Cota para Ingestão

## Pré-requisitos

- Collector e Core em execução com MongoDB, Redis e dependências de ingestão disponíveis.
- Uma assinatura de teste com `firstPaymentDate` conhecida e ciclo pago ativo.
- Perfis de teste para: saldo suficiente, saldo insuficiente, sem plano, antes do primeiro
  pagamento e tenant com `unlimitedUsage=true`.
- Token de serviço fornecido por ambiente; não registrar seu valor em arquivos versionados.
- Bytes canônicos da spec 005, contrato `reservatokens` e respostas por protocolo documentados.

## Cenário 1 — Reserva paralela e confirmação

1. Envie um lote válido para tenant com ciclo pago e saldo suficiente.
2. Interrompa a sanitização em ponto controlado e verifique `reserve` em paralelo, em
   `RESERVED`, com vencimento de 20 minutos.
3. Conclua a sanitização e verifique `confirm` antes de OpenObserve/stream.
4. Verifique reserva `CONFIRMED`, consumo efetivo e correlação com stream/fato de uso.
5. Repita `confirm` e comprove `204` sem segundo débito.

## Cenário 2 — Saldo insuficiente

1. Configure saldo em bytes menor que os bytes canônicos do lote.
2. Envie o mesmo lote por HTTP protobuf, HTTP JSON e gRPC.
3. Verifique HTTP `429`/`Retry-After: 120` e gRPC `RESOURCE_EXHAUSTED` conforme
   [contrato OTLP](contracts/otlp-quota-rejection.md), remoção temporária e ausência de
   OpenObserve/stream/consumo.
4. Reenvie enquanto o saldo não for atualizado e confirme que não há consumo.

## Cenário 3 — Sem ciclo pago

1. Use tenant sem assinatura, sem ciclo ativo ou com data civil UTC anterior a
   `firstPaymentDate`.
2. Envie lote válido.
3. Verifique HTTP `503`/`Retry-After: 120` e gRPC `UNAVAILABLE`, ausência de reserva
   confirmada e ausência de consumo.

## Cenário 4 — Falha após reserva e expiração

1. Autorize um lote e faça a sanitização rejeitá-lo antes de `confirm`.
2. Verifique `release` idempotente e saldo devolvido.
3. Simule queda do Collector depois de `reserve` e antes de `confirm`.
4. Avance o relógio ou aguarde 20 minutos.
5. Verifique estado `EXPIRED` e estorno único.

## Cenário 5 — Falha de confirmação

1. Faça `confirm` responder timeout, 5xx ou `409 RESERVATION_NOT_ACTIVE`.
2. Verifique HTTP `503`/`Retry-After: 120` ou gRPC `UNAVAILABLE` com
   `retry-after: 120`.
3. Verifique ausência de OpenObserve, stream/DLQ e fato de uso.

## Cenário 6 — Lote acima do saldo e Core indisponível

1. Configure saldo menor que os bytes canônicos completos do lote e envie-o.
2. Verifique recusa integral, sem aceitação parcial, HTTP `429`/gRPC
   `RESOURCE_EXHAUSTED` e ausência de publicação.
3. Indisponibilize o Core durante `reserve` e verifique falha fechada
   HTTP `503`/gRPC `UNAVAILABLE`, sem cache.

## Cenário 7 — `SALDO_BAIXO` idempotente

1. Confirme um lote que reduza o saldo de mais de 20% para 20% ou menos.
2. Verifique uma única mensagem conforme o
   [contrato](contracts/saldo-baixo-event.md), com `threshold: 20`.
3. Repita a confirmação e confirme que não há nova mensagem.
4. Confirme outro lote que cruze 5% e verifique uma única mensagem com `threshold: 5`.

## Cenário 8 — Compatibilidade com a medição existente

1. Execute lote aceito, lote parcial, reenvio idêntico e reenvio de `ABANDONED` conforme a
   spec 005.
2. Verifique preservação de domínio, bytes canônicos, `usageFactId`, ciclo e idempotência.
3. Verifique que não existe uma segunda métrica de bytes ou registros introduzida pela reserva.

## Cenário 9 — Reembolso pós-débito e recuperação

1. Confirme lote de 1 000 bytes e force falha de escrita no OpenObserve, stream e, em execução
separada, DLQ.
2. Verifique resposta `503`/`UNAVAILABLE` com retry de 120 segundos e um item de outbox
`PENDING` por `reservationId`, com razão correspondente.
3. Indisponibilize o Core durante o refund; verifique retry com backoff/teto e, ao restabelecer
o Core, `CONFIRMED -> REFUNDED` uma única vez.
4. Repita o refund e verifique `204`; force `409 RESERVATION_NOT_REFUNDABLE` e verifique
`DONE` terminal, sem novo retry. Confirme que `SALDO_BAIXO` não é reemitido.

Para o cenário automatizado do Collector, habilite somente no perfil E2E
`lhamalog.quota.e2e-fault-injection.enabled=true`. Um corpo contendo `__e2e_sanitizer_reject__` rejeita
durante a sanitização e deve liberar uma reserva já autorizada; um corpo contendo
`__e2e_write_fail__` força falha do sink e deve criar o refund durável. Ambos os gatilhos
permanecem inativos por padrão.

## Lista mínima de cenários E2E

- `429` sem saldo; `503` sem plano; Core fora (`503`).
- Lote acima do saldo recusado integralmente, sem escrita.
- Release quando a sanitização rejeita depois da reserva.
- Expiração TTL de reserva nunca confirmada, usando TTL curto configurado no perfil E2E.
- Replay idempotente de `confirm`.
- Reembolso após falha de OpenObserve/stream, incluindo Core fora durante refund e retry da outbox.
- `SALDO_BAIXO` uma vez em 20% e uma vez em 5% por ciclo.

## Configuração para E2E

No Collector, habilite `lhamalog.quota.enabled` (`LHAMALOG_QUOTA_ENABLED`), configure o Core em
`lhamalog.usage.core.url` (`LHAMALOG_USAGE_CORE_URL`) e ajuste `lhamalog.quota.timeout`
(`LHAMALOG_QUOTA_TIMEOUT`) conforme necessário. Para recuperação de refund use
`lhamalog.quota.refund-worker-interval` (`LHAMALOG_QUOTA_REFUND_WORKER_INTERVAL`),
`lhamalog.quota.refund-backoff-initial` (`LHAMALOG_QUOTA_REFUND_BACKOFF_INITIAL`) e
`lhamalog.quota.refund-backoff-ceiling` (`LHAMALOG_QUOTA_REFUND_BACKOFF_CEILING`).
Os gatilhos E2E permanecem desligados até
`lhamalog.quota.e2e-fault-injection.enabled` (`LHAMALOG_QUOTA_E2E_FAULT_INJECTION_ENABLED`) ser verdadeiro.

No Core, ajuste `lhamalog.usage.reservation-ttl` (`LHAMALOG_USAGE_RESERVATION_TTL`) e
`lhamalog.usage.reservation-reaper-interval` (`LHAMALOG_USAGE_RESERVATION_REAPER_INTERVAL`).
Para publicar alertas em Redis Streams, habilite
`lhamalog.notifications.redis.enabled` (`LHAMALOG_NOTIFICATIONS_REDIS_ENABLED`); o stream padrão é
`lhamalog:notifications:events` e pode ser alterado com `lhamalog.notifications.redis.stream`
(`LHAMALOG_NOTIFICATIONS_REDIS_STREAM`).

O saldo pode ser consultado autenticado pelo token de serviço em
`GET /internal/usage-reservations/balance?tenantId={tenantId}&productId={productId}`. A resposta é
`{"allowanceBytes":..., "reservedBytes":..., "consumedBytes":..., "remainingBytes":..., "cycleStart":"..."}`.

## Exemplo de cálculo

Plano de `0,000010 GB` decimal equivale a **10 000 bytes** (`1 GB = 1_000_000_000` bytes).
Reservar 3 000 bytes deixa `reservedBytes=3 000`; `confirm` move o valor para
`consumedBytes=3 000`, com **7 000 bytes restantes**. Um lote de 8 000 bytes é recusado
integralmente com `429` e `Retry-After: 120`, sem escrita.

Uma confirmação que deixe saldo em **2 000 bytes ou menos** cruza 20% e emite
`SALDO_BAIXO` 20 somente uma vez; em **500 bytes ou menos**, emite o limiar 5 somente uma
vez. Se um lote confirmado de 1 000 bytes falhar depois na escrita/publicação, seu refund
reduz `consumedBytes` em 1 000 uma única vez e não reemite `SALDO_BAIXO`. A expiração de uma
reserva nunca confirmada devolve somente `reservedBytes`.
