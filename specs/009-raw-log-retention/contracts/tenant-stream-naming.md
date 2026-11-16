# Contrato: nome de stream OpenObserve por tenant

## Entrada

- `prefix`: propriedade `lhamalog.otlp.openobserve.logs-stream` / `traces-stream` (env `OPENOBSERVE_LOGS_STREAM` / `OPENOBSERVE_TRACES_STREAM`). Default `logs` / `traces`. Passa a ser **prefixo**, não nome absoluto.
- `tenantId`: string não vazia do token.

## Algoritmo

1. `lower = tenantId.toLowerCase(Locale.ROOT)`
2. `sanitized = lower` com cada char fora de `[a-z0-9]` substituído por `_`
3. `truncated = sanitized` com no máximo **40** caracteres
4. `changed = !sanitized.equals(lower) || truncated.length() < sanitized.length()` (alterou ou truncou)
5. Se `changed`: `suffix = "_" + first8Hex(SHA-256(tenantId.getBytes(UTF_8)))`
6. Senão: `suffix = ""`
7. `streamName = prefix + "_" + truncated + suffix`

## Exemplos

| tenantId | prefix | streamName |
|----------|--------|------------|
| `acme` | `logs` | `logs_acme` |
| `Org-A` | `logs` | `logs_org_a_<8hex>` |
| `org_a` | `logs` | `logs_org_a` (se id já só `[a-z0-9_]`) — distinto de `Org-A` pelo hash |
| id > 40 chars sanitizados | `logs` | `logs_<40>_<8hex>` |

## Constraints OpenObserve

- Nome efetivo após normalização OO: chars fora de `[a-zA-Z0-9_:]` → `_`. Nossa regra já emite só `[a-z0-9_]`.
- Comprimento: manter razoável (prefixo + 40 + 1 + 8).

## Saída consumida por

- `OpenObserveClient.ingest(stream, …)`
- `StoredBatch.stream`
- `TelemetryIngestedBody.OpenObserveRef.stream` (Finder)
- `PUT .../streams/{stream}/settings`
