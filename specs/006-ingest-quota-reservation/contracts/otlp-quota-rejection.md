# Contrato Normativo — Recusa de Cota no OTLP

**Status**: aprovado para implementação.

## Regra comum

Quando a reserva não autorizar o lote, o Collector remove o lote temporário, não o grava no
OpenObserve, não o publica no stream e informa uma condição recuperável ao exporter. A
resposta não inclui saldo, plano, identificadores internos nem segredo.

## Mapeamento

| Condição do Core | HTTP protobuf | HTTP JSON | gRPC | Situação |
|---|---|---|---|---|
| Saldo insuficiente | `429` + `Retry-After: 120` | `429` + `Retry-After: 120` | `RESOURCE_EXHAUSTED` + `retry-after: 120` | recusado |
| Sem plano ou sem ciclo pago | `503` + `Retry-After: 120` | `503` + `Retry-After: 120` | `UNAVAILABLE` + `retry-after: 120` | recusado |
| Core indisponível, timeout ou 5xx | `503` + `Retry-After: 120` | `503` + `Retry-After: 120` | `UNAVAILABLE` + `retry-after: 120` | falha fechada |
| `confirm` retorna `RESERVATION_NOT_ACTIVE` | `503` + `Retry-After: 120` | `503` + `Retry-After: 120` | `UNAVAILABLE` + `retry-after: 120` | corta a execução |
| Falha de OpenObserve, stream ou DLQ após `confirm` | `503` + `Retry-After: 120` | `503` + `Retry-After: 120` | `UNAVAILABLE` + `retry-after: 120` | enfileira refund; reenvio reutiliza o `reservationId` e cria nova reserva se a anterior estiver `REFUNDED` |

O lote temporário é descartado em toda recusa, sem publicação, sem fato de uso e sem reserva
assumida sem resposta de sucesso. Core indisponível não usa cache de saldo ou limite.

## Compatibilidade

Os testes devem comprovar comportamento equivalente para OTLP HTTP protobuf, OTLP HTTP JSON
e OTLP gRPC. “Equivalente” significa a mesma decisão de aceitar ou recusar e orientação de
retry coerente; não exige corpo idêntico entre protocolos.
