# Contrato Normativo — Recusa de Cota no OTLP

**Status**: aprovado para implementação.

## Revisao 2026-11-16

Alinhado ao comportamento atual do Collector (LLT-183 + spec006):

- Saldo insuficiente permanece **120 s**.
- Sem plano / Core indisponível / timeout / 5xx / falha de `confirm` (incl. `RESERVATION_NOT_ACTIVE` mapeada como indisponibilidade de transição): **503 / UNAVAILABLE** com Retry-After / RetryInfo **300 s**, orçamento de **5** tentativas por lote; 6ª → HTTP **500** / gRPC **INTERNAL** sem retry.
- Pós-`confirm` (OpenObserve / stream / DLQ sem publicação): `DestinationUnavailable` com Retry-After **120 s** quando há `reservationId` (débito confirmado); sem reserva (caminho sem cota) permanece o default do sink (**5 s**).
- gRPC: orientação de retry via **`google.rpc.RetryInfo`** em `grpc-status-details-bin` (não mais trailer ASCII `retry-after`). HTTP mantém header `Retry-After` e, no corpo protobuf `google.rpc.Status`, o mesmo atraso em `RetryInfo` nos detalhes.
- Assinatura suspensa: HTTP **403** / gRPC **PERMISSION_DENIED**, sem retry (outcome Core `SUSPENDED`).

## Regra comum

Quando a reserva não autorizar o lote, o Collector remove o lote temporário, não o grava no
OpenObserve, não o publica no stream e informa uma condição recuperável ao exporter. A
resposta não inclui saldo, plano, identificadores internos nem segredo.

## Mapeamento

| Condição do Core / destino | HTTP protobuf | HTTP JSON | gRPC | Situação |
|---|---|---|---|---|
| Saldo insuficiente | `429` + `Retry-After: 120` (+ `RetryInfo` 120 s no Status) | `429` + `Retry-After: 120` | `RESOURCE_EXHAUSTED` + `RetryInfo` 120 s | recusado |
| Sem plano ou sem ciclo pago (`NO_PLAN`) | `503` + `Retry-After: 300` (tent. 1..5); 6ª → `500` | idem | `UNAVAILABLE` + `RetryInfo` 300 s; 6ª → `INTERNAL` sem RetryInfo | recusado; conta no orçamento Core-unavailable |
| Core indisponível, timeout ou 5xx | `503` + `Retry-After: 300` (tent. 1..5); 6ª → `500` | idem | `UNAVAILABLE` + `RetryInfo` 300 s; 6ª → `INTERNAL` sem RetryInfo | falha fechada; orçamento 5×/lote |
| `confirm` falha (ex.: `RESERVATION_NOT_ACTIVE` / não-204) | mesmo caminho Core indisponível (`503`/`300` com orçamento) | idem | idem | corta a execução; conta no orçamento |
| Falha de OpenObserve (0 aceitos), stream ou DLQ após `confirm` | `503` + `Retry-After: 120` (com reserva) | idem | `UNAVAILABLE` (sem `RetryInfo` neste caminho legado de destino; HTTP carrega Retry-After 120) | enfileira refund; reenvio reutiliza o `reservationId` e cria nova reserva se a anterior estiver `REFUNDED` |
| Recusa parcial do OpenObserve após `confirm` (`acceptedCount > 0`) | `200` parcial / equivalente | `200` parcial / equivalente | status parcial OTLP | **não** reembolsa; débito confirmado (bytes como recebidos) permanece; decisão `PartiallyAccepted` |
| Assinatura suspensa (`SUSPENDED`) | `403` sem Retry-After | `403` | `PERMISSION_DENIED` sem RetryInfo | recusado; sem orçamento Core-unavailable |

O lote temporário é descartado em toda recusa pré-gravação, sem publicação, sem fato de uso e sem reserva
assumida sem resposta de sucesso. Core indisponível não usa cache de saldo ou limite.

## Compatibilidade

Os testes devem comprovar comportamento equivalente para OTLP HTTP protobuf, OTLP HTTP JSON
e OTLP gRPC. “Equivalente” significa a mesma decisão de aceitar ou recusar e orientação de
retry coerente; não exige corpo idêntico entre protocolos (HTTP usa header `Retry-After`;
gRPC usa `RetryInfo` em status details).
