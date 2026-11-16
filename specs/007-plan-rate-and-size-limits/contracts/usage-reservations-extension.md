# Contrato — Extensão da API de reservas (Core) para LLT-183

**Base**: `../006-ingest-quota-reservation/contracts/reservatokens-api.md`  
**Endpoint**: `POST /internal/usage-reservations`  
**Auth**: `X-LhamaLog-Service-Token` (inalterado)

## Campos novos em PlanSubscription

Ver [data-model.md](../data-model.md): `status`, `overageEnabled`.

## Outcomes de `reserve` (extensão)

| Condição | HTTP | Body `outcome` | Notas |
|----------|------|----------------|-------|
| Autorizado (saldo ok) | 201 / 200 replay | `AUTHORIZED` | inalterado |
| Ilimitado | 201 / 200 | `AUTHORIZED_UNLIMITED` | inalterado |
| Saldo insuficiente, `overageEnabled=false` | 409 | `INSUFFICIENT_BALANCE` | inalterado |
| Saldo insuficiente, `overageEnabled=true` | 201 / 200 | `AUTHORIZED` + campo opcional `overageBytes` (>0) | Core emite `AUTHORIZED` (não `AUTHORIZED_OVERAGE`); registra excedente; **sem cobrança** |
| Assinatura `SUSPENSA` | **403** | `SUSPENDED` | `reservationId`/`expiresAt` null |
| Sem plano / sem ciclo / allowance inválido | 422 | `NO_PLAN` | inalterado; Collector → 503 + Retry-After **300** |
| INADIMPLENTE | **[NEEDS CLARIFICATION Q14]** | — | até decisão: tratar como ATIVA |

### Exemplo SUSPENSA

```http
HTTP/1.1 403 Forbidden
Content-Type: application/json

{
  "outcome": "SUSPENDED",
  "reservationId": null,
  "expiresAt": null
}
```

## Mapeamento no Collector (`HttpQuotaReservationClient`)

| HTTP Core | Outcome Collector | OTLP |
|-----------|-------------------|------|
| 403 + `SUSPENDED` | `SUSPENDED` | 403 / PERMISSION_DENIED |
| 409 | `INSUFFICIENT_BALANCE` | 429 / 120 |
| 422 | `NO_PLAN` | 503 / 300 |
| 201/200 AUTHORIZED* (+ `overageBytes` opcional) | `AUTHORIZED` | segue fluxo; campos extras ignorados no parse |
| outro / IO | `UNAVAILABLE` | 503 / 300 |

**Importante**: não tratar 403 de suspensão como `UnavailableException` genérico. Apenas `outcome=SUSPENDED` no body 403 vira `SUSPENDED`; 403 sem esse body permanece fail-closed (`UnavailableException` → 503).

### Exemplo overage (Core)

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "outcome": "AUTHORIZED",
  "reservationId": "quota:…",
  "expiresAt": "2026-11-16T12:00:00Z",
  "overageBytes": 42
}
```

Quando não há excedente, `overageBytes` é omitido (`null` no JSON).

## Fora de escopo neste contrato

- Endpoints de cobrança Asaas / emissão de fatura de excedente.
- API pública para mudar `status` / `overageEnabled` (pode ser seed/admin interno ou futuro ticket).
