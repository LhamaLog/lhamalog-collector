# Data Model: Associar logs ao tenant e ao produto (LLT-182)

## Documento no OpenObserve (sem mudança)

Continua como em `specs/001-otlp-receiver/data-model.md`: `tenantId` e `productId` do token no topo,
`attributes` e `resourceAttributes` em JSON, `serviceName`, `scopeName`, `scopeVersion`.

## Entrada do stream `lhamalog:stream:logs`

Uma entrada por log aceito. Campos (todos texto):

| Campo | Valor |
|---|---|
| `idempotencyId` | UUID v3 determinístico (research D5) |
| `event` | `log.ingested` |
| `origin` | `collector` |
| `schemaVersion` | `1` |
| `publishedAt` | instante ISO-8601 UTC, ex. `2026-11-01T15:00:00Z` |
| `metadata` | JSON, ver abaixo |
| `body` | JSON, ver abaixo |

### metadata

| Chave | Obrigatória | Origem |
|---|---|---|
| `tenantId` | sim | token; só aparece aqui |
| `productId` | sim | token |
| `correlationId` | sim | UUID por chamada de exportação |
| `traceId` | quando o log tem | hex de 32 caracteres |

### body (`LogIngestedBody`, versão 1)

| Campo | Tipo | Observação |
|---|---|---|
| `receivedAt` | texto ISO | instante do aceite |
| `timeUnixNano` | texto decimal ou null | sem sinal |
| `observedTimeUnixNano` | texto decimal ou null | |
| `severityNumber` | número | |
| `severityText` | texto ou null | |
| `body` | texto ou null | não-string vira JSON em texto |
| `attributes` | objeto | atributos do log como vieram |
| `traceId` / `spanId` | hex ou null | |
| `flags` | número | |
| `resource.serviceName` | texto ou null | de `service.name` |
| `resource.attributes` | objeto | atributos do recurso como vieram |
| `scope.name` / `scope.version` | texto ou null | |
| `scope.attributes` | objeto | |

Valores de atributo: string, booleano, inteiro, decimal, bytes em Base64, listas e mapas aninhados.
Atributos chamados `tenantId`/`productId` vindos do lote ficam dentro de `attributes`, sem efeito na
identidade.

## Limite do stream

`MAXLEN ~ N`, N = `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` ou 100000. Valor não numérico ou menor que 1
impede a subida.
