# Contrato — `metadata.correlationId` nos streams (Finder)

**Status:** aprovado (Michel, 16/11/2026 — T050).  
**Escopo:** envelope publicado pelo Collector em `lhamalog:stream:logs` / `lhamalog:stream:traces` e nas DLQs correspondentes.  
**Consumidor previsto:** Finder (`lhamalog-finder/internal/adapter/in/queue`) — **ainda não implementado**; esta mudança não quebra consumidor em produção.

## O que não muda

- **Não há campo novo** no envelope. Continua existindo `metadata.correlationId` (já previsto em spec002 / spec004).
- `schemaVersion` permanece **1** (compatível com versões anteriores do envelope).
- `idempotencyId` (= `batchId` determinístico) continua sendo a chave de deduplicação.

## Semântica (a partir de LLT-183 / T050)

| Aspecto | Valor |
|---|---|
| Origem | Correlation id da requisição OTLP: header HTTP `X-Correlation-Id` ou metadata gRPC `x-correlation-id`, validado por `CorrelationId` (`^[A-Za-z0-9._-]{1,64}$`); se ausente ou inválido, UUID gerado na entrada (ecoado na resposta). |
| Formato | String opaca de 1..64 caracteres no charset acima — **não precisa ser UUID**. |
| Repetição | Pode se repetir entre requisições/mensagens (cliente pode reenviar o mesmo cid). |
| DLQ | A entrada na DLQ leva o **mesmo** `metadata` do envelope original, inclusive o mesmo `correlationId`. |

## Obrigações do consumidor (Finder)

1. **MUST** deduplicar por `idempotencyId` (nunca por `correlationId`).
2. **MUST NOT** assumir unicidade global de `correlationId` entre lotes ou reenvios.
3. **MAY** usar `correlationId` só para correlação/observabilidade (agrupar com logs do Collector/Core).

## Referências

- Envelope / DLQ: `specs/004-publish-read-queue/data-model.md`, `specs/004-publish-read-queue/contracts/dlq-stream.md`
- Stream de referência: `specs/002-tenant-product-logs/contracts/log-ingested-stream.md`
- Implementação: `TelemetryIngestedPublisher` + `logging/CorrelationId`
