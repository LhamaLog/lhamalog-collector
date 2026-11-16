# Modelo de Dados — Reserva de Cota

## Reserva de Cota

| Campo | Regra |
|---|---|
| `reservationId` | Identificador imutável gerado pelo Collector, estável por lote, usado como chave de idempotência e propagado para rastreio. |
| `tenantId` / `productId` | Identidade extraída do token do produto; obrigatória. |
| `requestedBytes` | `long` positivo: bytes canônicos do lote elegível, iguais a `acceptedBytes` da spec 005. |
| `state` | `RESERVED`, `CONFIRMED`, `REFUNDED`, `RELEASED` ou `EXPIRED`. |
| `expiresAt` | 20 minutos após a reserva; obrigatório para `RESERVED`. |
| `batchId` | Vínculo com o lote determinístico da medição existente, quando já disponível. |
| `usageFactId` | Vínculo opcional ao fato de uso existente para correlação; não altera a idempotência da medição. |
| `createdAt` / `updatedAt` | Instantes auditáveis em UTC. |

### Transições

```text
nova solicitação -> RESERVED
RESERVED -> CONFIRMED  (Collector confirmou após sanitização)
RESERVED -> RELEASED   (sanitização rejeitada/falhou antes da confirmação)
RESERVED -> EXPIRED    (TTL de 20 minutos)
CONFIRMED -> REFUNDED  (refund CAS após falha de escrita/publicação)
REFUNDED, RELEASED, EXPIRED -> terminal
```

Operações repetidas com a mesma chave idempotente devolvem o resultado previamente
estabelecido e não repetem a alteração de saldo.

## Saldo de Ciclo

| Campo | Regra |
|---|---|
| `tenantId` / `productId` | Chave do contrato já usada pelo ciclo de uso; logs e traces compartilham este saldo. |
| `cycleFrom` / `cycleTo` | Derivados no Core pela assinatura e `firstPaymentDate`. |
| `allowanceBytes` | Limite contratado em bytes; plano exibido em GB decimal (`1 GB = 1_000_000_000 bytes`). |
| `reservedBytes` | Parcela temporariamente retida por reservas `RESERVED`. |
| `consumedBytes` | Parcela confirmada após efetivação. |
| `unlimitedUsage` | Marcador explícito em `PlanSubscription`; exige plano e ciclo pago e não debita saldo. |

Invariantes:

- Antes de `firstPaymentDate` em data civil UTC, não há ciclo pago e não se reserva.
- `confirmed` nunca é liberado.
- `confirmed` somente pode ser compensado por `refund`; o CAS reduz `consumedBytes` uma vez.
- Uma expiração estorna exatamente a quantidade ainda reservada.
- `remainingBytes = allowanceBytes - reservedBytes - consumedBytes`.
- Reserva usa atualização atômica condicional para nunca deixar `remainingBytes` negativo.

## Lote Temporário no Collector

| Campo | Regra |
|---|---|
| `batchId` | Identidade do lote conforme spec 005 quando o subconjunto aceito estiver definido. |
| `tenantId` / `productId` / `domain` | Derivados do contexto autenticado e do tipo OTLP; domínio não separa o saldo. |
| `reservationId` | Vazio antes da autorização; obrigatório no encaminhamento autorizado. |
| `state` | `PENDING_RESERVATION`, `AUTHORIZED`, `REJECTED`, `RELEASED` ou `EXPIRED`. |
| `payloadReference` | Referência temporária, não um novo formato de medição. |

O mecanismo físico de armazenamento temporário não é decidido por esta documentação; ele deve
garantir remoção em recusa e não publicar antes de `AUTHORIZED`.

## Relação com dados existentes

- `PlanSubscription` do Core continua responsável por `planType` e `firstPaymentDate`.
- `UsageCycle` continua definindo intervalo do ciclo em UTC.
- `UsageFact` e `UsageOutboxItem` preservam identidade, bytes canônicos e estados atuais.
- O stream pode receber `reservationId` exclusivamente para rastreabilidade; a confirmação é
  feita pelo Collector antes da escrita/publicação.

## Emissão de Saldo Baixo

| Campo | Regra |
|---|---|
| `idempotencyKey` | `tenantId+productId+cycleStart+threshold`; único no Mongo. |
| `threshold` | `20` ou `5`, percentual da franquia do ciclo. |
| `type` / `severity` | `SALDO_BAIXO` / `MEDIUM`. |
| `remainingBytes` | Saldo calculado depois de `confirm` que debitou. |
| `mandatoryRoles` | `[DONO, ADMINISTRADOR]`. |
| `mandatoryChannels` | `[WEB, EMAIL]`. |
| `state` | `PENDING` ou `PUBLISHED` na outbox Mongo. |

A matriz de destinatários e a entrega WEB/E-MAIL pertencem ao módulo Notificação. Este
serviço somente produz o evento do contrato e o publica de forma confiável.

## Outbox de Reembolso no Collector

| Campo | Regra |
|---|---|
| `reservationId` | Único; chave de idempotência do pedido. |
| `tenantId` / `productId` / `bytes` | Contexto auditável, sem token. |
| `reason` | `OPENOBSERVE_WRITE_FAILED`, `STREAM_PUBLISH_FAILED` ou `DLQ_PUBLISH_FAILED`. |
| `state` | `PENDING`, `IN_FLIGHT`, `DONE` ou `SUSPENDED`. |
| `attemptCount` / `nextAttemptAt` / `lastErrorCode` | Retry com backoff e teto; data BSON-safe; `401`/`403` suspendem. |

`409 RESERVATION_NOT_REFUNDABLE` é terminal (`DONE`). O reembolso não apaga nem recria
chaves `SALDO_BAIXO` já emitidas.
