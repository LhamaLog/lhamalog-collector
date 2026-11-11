# Contrato Normativo — API Interna de Reserva de Cota

**Status**: aprovado para implementação.

## Objetivo

Permitir ao Collector reservar, confirmar e liberar saldo no Core para um lote identificado,
com repetição segura de chamadas.

## Operações lógicas

| Operação | Entrada mínima | Resultado |
|---|---|---|
| `POST /internal/usage-reservations` | `reservationId`, tenant, produto, bytes, domínio e lote opcional | `AUTHORIZED`, `AUTHORIZED_UNLIMITED`, `INSUFFICIENT_BALANCE` ou `NO_PLAN` |
| `POST /internal/usage-reservations/{reservationId}/confirm` | identificador de reserva | `204`, incluindo replay |
| `POST /internal/usage-reservations/{reservationId}/release` | identificador de reserva | `204`, incluindo replay |
| `POST /internal/usage-reservations/{reservationId}/refund` | identificador de reserva | `204`, incluindo replay |

## Requisição conceitual de reserva

```json
{
  "reservationId": "id-estavel-gerado-pelo-collector",
  "tenantId": "derivado-do-token",
  "productId": "derivado-do-token",
  "requestedBytes": 1234,
  "batchId": "opcional",
  "domain": "LOGS ou TRACES"
}
```

Nenhum valor de token é exemplo ou parte do corpo.

## Resposta conceitual

```json
{
  "outcome": "AUTHORIZED | AUTHORIZED_UNLIMITED | INSUFFICIENT_BALANCE | NO_PLAN",
  "reservationId": "presente-quando-autorizado",
  "expiresAt": "instante-UTC-quando-autorizado"
}
```

`requestedBytes` é `long` positivo e contém os bytes canônicos do lote elegível, iguais a
`acceptedBytes` da spec 005. `AUTHORIZED_UNLIMITED` requer `PlanSubscription` aplicável,
ciclo pago e `unlimitedUsage=true`, e não debita saldo. `NO_PLAN` cobre ausência de plano,
ciclo pago ou data UTC anterior a `firstPaymentDate`.

## Regras de transição

- `reserve` é idempotente por `reservationId` estável do lote; replay retorna `200`, e a
  primeira autorização retorna `201`.
- `confirm` e `release` retornam `204` para sucesso ou replay e `404` para identificador desconhecido.
- `confirm` em reserva `RELEASED` ou `EXPIRED` retorna `409` com código
  `RESERVATION_NOT_ACTIVE`, nunca debita e deve levar o Collector a cortar a execução.
- `release` só devolve uma reserva ainda `RESERVED`; em `CONFIRMED`, retorna `409` e não
  reembolsa.
- `refund` faz `CONFIRMED -> REFUNDED` atomicamente e subtrai `requestedBytes` de
  `consumedBytes` exatamente uma vez. Em replay de `REFUNDED`, retorna `204`; reserva
  desconhecida retorna `404`; `RESERVED`, `RELEASED` e `EXPIRED` retornam `409
  RESERVATION_NOT_REFUNDABLE`. Para `AUTHORIZED_UNLIMITED`, retorna `204` sem saldo.
- `reserve` com identificador anteriormente `REFUNDED`, `RELEASED` ou `EXPIRED` cria nova
  reserva para nova tentativa do lote.
- O processo de expiração move reserva pendente para `EXPIRED` após 20 minutos.
- Nenhuma operação expõe segredos em corpo, log, teste ou documentação.

## Autenticação e transporte

Todas as rotas são HTTP internas, protegidas exclusivamente por
`X-LhamaLog-Service-Token` normalizado/trim, como usage-facts, e não aceitam cabeçalho
`Authorization`. `401` indica token ausente ou inválido. Nunca documentar seu valor.

## Ordem de integração do Collector

O Collector executa `reserve` em paralelo à sanitização/ocultação. Ao concluir a
sanitização, chama `confirm` e, somente em sucesso, grava no OpenObserve e publica no
stream/DLQ. `reservationId` pode ser propagado na mensagem apenas para rastreabilidade.
Se o Collector cair entre reserva e confirmação, o Core expira `RESERVED` após 20 minutos e
estorna o saldo.

Depois de `confirm`, falha em OpenObserve, stream ou DLQ mantém a resposta OTLP
`503`/`UNAVAILABLE` e enfileira `refund` em outbox Mongo durável no Collector. `refund` não
reverte nem reemite alertas `SALDO_BAIXO` já registrados no ciclo.
