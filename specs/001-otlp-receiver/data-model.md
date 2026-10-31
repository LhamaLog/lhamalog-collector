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
| `DestinationUnavailable` | Havia registros válidos e o destino não confirmou | tentativa falhou; nada é confirmado ao remetente |

Transição: autenticar → classificar registros → ou rejeitar, ou entregar, ou
devolver indisponível. Falha do destino no meio do lote válido termina em
`DestinationUnavailable`, mesmo que alguns registros já tenham sido recusados
na classificação.

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
| `_timestamp` | tempo do log, em microssegundos; se faltar, o instante de aceite |
| `receivedAt` | instante em que o collector aceitou |
| `traceId` | hex dos 16 bytes, quando houver |
| `spanId` | hex dos 8 bytes, quando houver |
| `severityNumber` | do registro |
| `body` | texto do body, quando houver |
| `attributes` | atributos do registro em texto JSON, sem usar isso como produto |

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
| `attributes` | atributos do span em texto JSON, sem usar isso como produto |

O envio é um array desses objetos em `POST /api/lhamalog/traces/_json`, com a
mesma regra de confirmação dos logs (HTTP 200, `failed` igual a zero e
`successful` igual à quantidade enviada). Reenvio cria outros registros.

## O que não é entidade desta feature

- Métricas e profiles.
- Cadastro, rotação e revogação feitos por esta aplicação.
- Índice de busca, retenção e alerta no OpenObserve.
- Cadastro, rotação e revogação de `ProductToken`.
