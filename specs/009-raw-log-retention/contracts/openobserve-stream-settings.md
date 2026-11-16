# Contrato: retenção via OpenObserve stream settings

## Endpoint

```http
PUT /api/{organization}/streams/{stream}/settings?type={logs|traces}
Authorization: Basic …
Content-Type: application/json

{"data_retention": 30}
```

- `organization`: `lhamalog.otlp.openobserve.organization` (default `lhamalog`).
- `stream`: nome por tenant.
- `type`: `logs` para sink de logs; `traces` para sink de traces.
- `data_retention`: inteiro > 0 (dias). Sobrescreve retenção global `ZO_COMPACT_DATA_RETENTION_DAYS` naquele stream.

## Quando chamar

1. Ingest `POST /api/{org}/{stream}/_json` confirmado com sucesso.
2. `retentionDays` conhecido (> 0) vindo da reserva.
3. Cache Caffeine **não** contém `(type, stream) → N` válido (TTL 1 h); ou N mudou.
4. Fora de janela de negative-cache (falha recente, ~60 s).

## Quando NÃO chamar

- `retentionDays` unknown (ausente/null/≤0).
- Quota desligada (sem reserva).
- Cache hit com mesmo N.
- Dentro do backoff após falha.

## Semântica de falha

- HTTP ≠ 2xx, timeout, rede: log WARN  
  `event=openobserve.stream_retention.apply_failed | … | stream=… type=… retentionDays=… status=…`  
  (ids de tenant/product só se já no padrão LLT-183 do fluxo; sem payload).
- Contador: `lhamalog.openobserve.retention.apply{outcome=failed,type=…}`.
- Sucesso: `outcome=applied`; cachear N.
- **Nunca** propagar a falha para a decisão OTLP do lote.

## Verificação (teste)

```http
GET /api/{org}/streams/{stream}/schema?type=logs
```

Assert `settings.data_retention` (ou caminho equivalente na resposta v1.1.2) == N.

## Escolha de execução (documentada)

Aplicação **assíncrona** após o store (executor dedicado / virtual thread), com `CorrelationId.propagate`, para não atrasar a resposta OTLP. Timeout curto no HTTP do PUT (ex. 5 s).
