# Data model — métricas operacionais do Collector

## OperationalMeter (conceitual)

| Campo | Descrição |
|-------|-----------|
| name | Nome Micrometer (dots); Prometheus: underscores + `_total` em counters |
| type | counter \| timer (histogram) \| gauge |
| tags | Conjunto fechado por meter |
| updateMoment | Quando incrementa/atualiza |

### Cinco métricas novas

| name | type | tags | updateMoment |
|------|------|------|--------------|
| `lhamalog.ingestion.accepted.records` | counter | signal, protocol | Após sink/publish ok |
| `lhamalog.ingestion.accepted.bytes` | counter | signal, protocol | Mesmo ponto; valor = bytes canônicos |
| `lhamalog.ingestion.latency` | timer | signal, protocol, outcome | Fim do handling no receiver |
| `lhamalog.queue.size` | gauge | signal | Refresh agendado (XLEN stream) |
| `lhamalog.dlq.size` | gauge | signal | Refresh agendado (XLEN DLQ) |

Valores de tag:

- `signal`: `logs` \| `traces`
- `protocol`: `http` \| `grpc`
- `outcome`: `accepted` \| `refused` \| `failed`

## QueueDepthSample

| Campo | Tipo | Notas |
|-------|------|-------|
| signal | enum | logs/traces |
| queueSize | double | XLEN principal; NaN se nunca ok |
| dlqSize | double | XLEN DLQ |
| lastSuccessAt | Instant? | null até primeiro sucesso |
| lastErrorAt | Instant? | para rate-limit de WARN |

## ScrapeSurface

| Campo | Valor |
|-------|-------|
| port | MANAGEMENT_PORT default 9464 |
| address | MANAGEMENT_ADDRESS default vazio |
| exposed | health, info, prometheus |
| forbiddenTags | tenantId, tenant_id, tenant, productId, product_id, token |

## LogLine (princípio VI — não muda schema)

Campos parseados pelo Alloy para metadata: `level`, `cid`, `logger`, `event` (quando presente na mensagem). Labels Loki: `service`, `level` (+ `env` se configurado).
