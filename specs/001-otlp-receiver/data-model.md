# Data Model: Recepção OTLP de logs e traces

Modelo lógico desta feature. Nomes de campo persistidos ficam em inglês, como
o código. Não há API de cadastro de produto aqui: o collector só lê `products`.

## ProductCredential

Documento lido da coleção MongoDB `products`.

| Campo | Tipo | Regra |
|---|---|---|
| `productId` | string | Obrigatório. Identidade gravada no destino. |
| `tokenHash` | string | Obrigatório. SHA-256, hex minúsculo, dos bytes UTF-8 do token. Único. |
| `status` | `ACTIVE` ou `REVOKED` | Obrigatório. |
| `expiresAt` | instante ou ausente | Ausente significa sem prazo. |

**Válido** quando `status` é `ACTIVE` e `expiresAt` está ausente ou é posterior
ao instante da requisição. Qualquer outro caso é não autorizado.

**Relação**: um hash aponta para um produto. Dois documentos com o mesmo hash
são configuração inválida; o collector recusa o token.

O valor do token não é campo deste modelo.

## IngestionDecision

Resultado do caso de uso, antes de virar HTTP ou gRPC.

| Estado | Quando | Destino |
|---|---|---|
| `Unauthorized` | Token ausente, vazio, desconhecido, revogado ou expirado | não é chamado |
| `CatalogUnavailable` | A leitura de `products` falha | não é chamado |
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
fica no primeiro nível.

## SpanAssessment

Um item de `ExportTraceServiceRequest`. Validade na seção 8 de
[research.md](research.md).

Entrada aceita no Redis Stream `lhamalog:traces`:

| Campo | Origem |
|---|---|
| `productId` | token, sempre |
| `traceId` | hex do span |
| `spanId` | hex do span |
| `name` | do span |
| `receivedAt` | instante em que o collector aceitou |
| `payload` | span aceito serializado para o consumidor |

Um `XADD` por span aceito. Sem `MAXLEN` nesta feature.

## O que não é entidade desta feature

- Métricas e profiles.
- Cadastro, rotação e revogação feitos por esta aplicação.
- Índice de busca, retenção, alerta e consumidor do stream.
