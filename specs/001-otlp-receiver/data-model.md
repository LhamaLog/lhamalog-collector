# Data Model: Recepção OTLP de logs e traces

Modelo lógico desta feature. Nomes de campo persistidos ficam em inglês, como
o código. Não há API de cadastro de produto nem de token aqui: o collector só
lê `product_tokens` e `products`.

## ProductToken

Documento lido da coleção MongoDB `product_tokens`. Substitui o antigo
`ProductCredential`: o `tokenHash` não fica mais dentro de `products`.

| Campo | Tipo | Regra |
|---|---|---|
| `id` | identificador do documento | Obrigatório. Gerado pelo MongoDB. |
| `productId` | string | Obrigatório. Referência a um documento existente em `products`. Identidade gravada no destino. |
| `tokenHash` | string | Obrigatório. SHA-256, hex minúsculo, dos bytes UTF-8 do token. Único na coleção. |
| `status` | `ACTIVE` ou `REVOKED` | Obrigatório. |
| `createdAt` | instante | Obrigatório. Momento do cadastro do token. |
| `revokedAt` | instante ou ausente | Preenchido na revogação. Ausente enquanto o token está ativo. |
| `expiresAt` | instante ou ausente | Opcional. Ausente significa sem prazo. |

**Válido** quando, ao mesmo tempo:

- `status` é `ACTIVE`;
- `revokedAt` está ausente;
- `expiresAt` está ausente ou é posterior ao instante da requisição;
- o `productId` aponta para um documento existente em `products`, com
  `tenantId` preenchido.

Qualquer outro caso é não autorizado. Dois documentos com o mesmo `tokenHash`
são configuração inválida; o collector recusa o token.

**Ciclo de vida** (regra documentada aqui, executada fora desta feature):

- Um token nunca é apagado fisicamente. Revogar só muda `status` para
  `REVOKED` e preenche `revokedAt`.
- A exclusão física de um `ProductToken` só acontece em cascata, quando o
  documento de `products` relacionado é apagado.
- Cadastro, rotação e revogação não são feitos pelo collector. O collector só
  lê `product_tokens` e confere a existência e o `tenantId` do produto em
`products`.

O valor do token não é campo deste modelo.

## Relacionamento products 1:N product_tokens

```text
products (1) ──< product_tokens (N)
   productId        productId (referência obrigatória)
```

Um produto pode ter vários tokens ao mesmo tempo (por exemplo, durante uma
rotação). Cada token aponta para exatamente um produto. O collector lê de
`products` só o necessário para saber se o produto relacionado existe e qual
o seu `tenantId`.

## Product (campos lidos)

O collector lê de `products` só o necessário:

| Campo | Tipo | Regra |
|---|---|---|
| `productId` | string | Obrigatório. Chave usada por `product_tokens`. |
| `tenantId` | string | Obrigatório. Tenant ao qual o produto pertence (ADR_121/ADR_168). |

Cada produto pertence a exatamente um tenant. Produto sem `tenantId` é cadastro
inválido: o token é tratado como não autorizado.

## tenantId

A ADR_121 e a ADR_168 exigem isolamento por `tenantId` nos dados gravados no
OpenObserve. O collector resolve o `tenantId` pelo produto do token
(`product_tokens.productId` → `products.tenantId`) e grava `tenantId` e
`productId` em cada log e span aceito. Valores de tenant ou produto vindos do
lote não substituem nenhum dos dois.

## IngestionDecision

Resultado do caso de uso, antes de virar HTTP ou gRPC.

| Estado | Quando | Destino |
|---|---|---|
| `Unauthorized` | Token ausente, vazio, desconhecido, revogado ou expirado | não é chamado |
| `CatalogUnavailable` | A leitura de `product_tokens` ou `products` falha | não é chamado |
| `InvalidPayload` | Corpo ilegível, encoding não suportado, acima do limite, ou todos os registros inválidos | não é chamado |
| `EmptySuccess` | Lote bem formado com zero registros | não é chamado |
| `Accepted` | Um ou mais registros válidos e o destino confirmou todos eles | recebe só os válidos |
| `PartiallyAccepted` | Há rejeitados e o destino confirmou os válidos | recebe só os válidos |
| `InvalidPayload` (`REJECTED_BY_DESTINATION`) | O destino recusou todos os registros enviados como dado inválido (HTTP 400/413/415/422 ou `failed` igual ao total) | foi chamado e recusou; não repetível (HTTP 400, gRPC `INVALID_ARGUMENT`) |
| `DestinationUnavailable` | Havia registros válidos e o destino não confirmou (5xx, 401/403/404/408/429, timeout, conexão, resposta não conferível) | tentativa falhou; nada é confirmado ao remetente |

Transição: autenticar → classificar registros → ou rejeitar, ou entregar, ou
devolver indisponível. Falha do destino no meio do lote válido termina em
`DestinationUnavailable`, mesmo que alguns registros já tenham sido recusados
na classificação. Se o destino recusa só parte dos registros como dado
inválido (HTTP 200 com `failed` > 0 e `successful + failed` igual ao enviado),
o resultado é `PartiallyAccepted`, somando esses aos rejeitados na classificação.

## TimestampWindow

Janela de horário aceita (FR-019), configurada em `lhamalog.otlp.timestamp.*`:
`max-age` (padrão `290m` = 4h50, abaixo das 5 h de `ZO_INGEST_ALLOWED_UPTO` do
OpenObserve) e `max-future` (padrão `1h`). Um registro é rejeitado, com motivo
contado no aceite parcial, quando seu horário efetivo é anterior a
`agora - max-age` ou posterior a `agora + max-future`. Horário efetivo zero
(ausente) não é checado e vira o instante de recebimento no `_timestamp`.

## Scoped

Par registro + `Resource` + `InstrumentationScope` de origem. A classificação
devolve `Scoped<LogRecord>`/`Scoped<Span>` para que os sinks gravem o contexto
OTLP (FR-021).

## LogRecordAssessment

Um item de `ExportLogsServiceRequest`.

**Válido** quando passa nas regras de [research.md](research.md) seção 8.
**Inválido** caso contrário. A resposta de rejeição parcial conta registros
inválidos, não resources nem scopes.

Objeto JSON aceito no stream OpenObserve `logs` (organização `lhamalog`):

| Campo | Origem |
|---|---|
| `tenantId` | produto do token, sempre |
| `productId` | token, sempre |
| `_timestamp` | horário efetivo do log em microssegundos: `timeUnixNano`; se 0, `observedTimeUnixNano`; se 0, o instante de aceite (FR-023) |
| `receivedAt` | instante em que o collector aceitou |
| `traceId` | hex dos 16 bytes, quando houver |
| `spanId` | hex dos 8 bytes, quando houver |
| `timeUnixNano` | do registro |
| `observedTimeUnixNano` | do registro, como texto decimal sem sinal |
| `severityNumber` | do registro |
| `severityText` | do registro, quando houver |
| `flags` | do registro (inteiro sem sinal) |
| `body` | texto do body, quando houver |
| `attributes` | atributos do registro em texto JSON, sem usar isso como produto |
| `serviceName` | atributo `service.name` (string) do resource, quando houver |
| `resourceAttributes` | atributos do resource em texto JSON |
| `scopeName` / `scopeVersion` | do `InstrumentationScope`, quando houver |

O envio é um array desses objetos em
`POST /api/lhamalog/logs/_json`. Reenvio cria outros registros. O OpenObserve
achataria objeto aninhado; por isso `attributes` vai como texto, e `productId`
e `tenantId` ficam no primeiro nível.

## SpanAssessment

Um item de `ExportTraceServiceRequest`. Validade na seção 8 de
[research.md](research.md).

Objeto JSON aceito no stream OpenObserve de traces (padrão `traces`,
configurável; organização `lhamalog`):

| Campo | Origem |
|---|---|
| `tenantId` | produto do token, sempre |
| `productId` | token, sempre |
| `_timestamp` | início do span, em microssegundos; se faltar, o instante de aceite |
| `receivedAt` | instante em que o collector aceitou |
| `traceId` | hex dos 16 bytes |
| `spanId` | hex dos 8 bytes |
| `parentSpanId` | hex dos 8 bytes, quando houver |
| `name` | do span |
| `startTimeUnixNano` | do span |
| `endTimeUnixNano` | do span |
| `traceState` | do span, quando houver |
| `kind` | nome do enum (`SPAN_KIND_SERVER`, ...) |
| `statusCode` / `statusMessage` | nome do enum (`STATUS_CODE_OK`, ...) e mensagem |
| `events` | eventos em texto JSON (nome, horário, atributos) |
| `links` | links em texto JSON (`traceId`/`spanId` em hex, `traceState`, atributos) |
| `attributes` | atributos do span em texto JSON, sem usar isso como produto |
| `serviceName` | atributo `service.name` (string) do resource, quando houver |
| `resourceAttributes` | atributos do resource em texto JSON |
| `scopeName` / `scopeVersion` | do `InstrumentationScope`, quando houver |

O envio é um array desses objetos em `POST /api/lhamalog/traces/_json`, com a
mesma regra de confirmação dos logs (HTTP 200, `failed` igual a zero e
`successful` igual à quantidade enviada). Reenvio cria outros registros.

## Nomes de campo no OpenObserve

O collector envia os nomes em camelCase, mas o OpenObserve grava as colunas em
minúsculas. Nas buscas SQL use: `tenantid`, `productid`, `receivedat`,
`traceid`, `spanid`, `parentspanid`, `timeunixnano`, `observedtimeunixnano`,
`severitynumber`, `severitytext`, `starttimeunixnano`, `endtimeunixnano`,
`tracestate`, `statuscode`, `statusmessage`, `servicename`,
`resourceattributes`, `scopename`, `scopeversion` (`body`, `name`, `kind`,
`flags`, `events`, `links`, `attributes` e `_timestamp` não mudam).

## O que não é entidade desta feature

- Métricas e profiles.
- Cadastro, rotação e revogação feitos por esta aplicação.
- Índice de busca, retenção e alerta no OpenObserve.
- Cadastro, rotação e revogação de `ProductToken`.
