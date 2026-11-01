# Contract: exportação OTLP

Contrato externo do receptor. A decisão de negócio está em
[data-model.md](../data-model.md). O mapa completo e o porquê de cada código
estão em [research.md](../research.md) seção 7.

Os dois transportes executam o mesmo caso de uso. Mensagens de erro e
`error_message` do sucesso parcial saem em inglês.

Token no header `Authorization: Bearer <token>`. O valor não aparece em
exemplo commitado.

## HTTP

Porta padrão de produção: 4318. No perfil `local`: 4328.

| Método e caminho | Corpo da requisição |
|---|---|
| `POST /v1/logs` | `ExportLogsServiceRequest` |
| `POST /v1/traces` | `ExportTraceServiceRequest` |

`Content-Type`: `application/x-protobuf` ou `application/json`.
`Content-Encoding: gzip` é aceito. Outro encoding recebe 415.

No JSON, `traceId` e `spanId` são hex (não base64) e enums são inteiros, como
na spec OTLP.

| Resultado | HTTP | Corpo | Header extra |
|---|---|---|---|
| Aceite total ou lote vazio | 200 | `ExportLogsServiceResponse` ou `ExportTraceServiceResponse`, sem `partial_success` | |
| Aceite parcial | 200 | o mesmo, com `partial_success.rejected_log_records` ou `rejected_spans`, e `error_message` | |
| Não autorizado | 401 | `google.rpc.Status` | `WWW-Authenticate: Bearer` |
| Lote ou registro irrecuperável, ou todos recusados pelo OpenObserve como dado inválido | 400 | `google.rpc.Status` | |
| Acima do limite (padrão 64 MiB já descomprimido, ou `Content-Length` declarado acima dele) | 413 | `google.rpc.Status` | |
| `Content-Type` ou `Content-Encoding` não suportado | 415 | `google.rpc.Status` | |
| Destino (5xx, timeout, conexão) ou catálogo de tokens fora, ou servidor HTTP no limite de requisições simultâneas | 503 | `google.rpc.Status` | `Retry-After` em segundos (`1` quando no limite) |
| Outro caminho, inclusive `/v1/metrics` | 404 | `google.rpc.Status` | |
| Método diferente de POST | 405 | `google.rpc.Status` | `Allow: POST` |

O `Content-Type` da resposta repete o da requisição. 401, 400, 413 e 415 não são
para retry. 503 é.

Ordem das checagens: método e caminho, depois token (401), e só então
`Content-Type`, `Content-Encoding` e tamanho (415/413). Sem token válido a
resposta é sempre 401, mesmo com content type inválido.

Registro com horário fora da janela (mais antigo que 4h50 ou mais de 1 h no
futuro, por padrão; no log vale `timeUnixNano`, senão `observedTimeUnixNano`)
é rejeitado e contado em `partial_success`; se todos estiverem fora, 400.

Se o OpenObserve recusar dados (HTTP 400/413/415/422, ou 200 com `failed` > 0 e
`successful + failed` igual ao enviado), a resposta é 400 quando nada foi
aceito, ou 200 com `partial_success` somando os recusados. Nunca 503: reenviar
o mesmo lote não resolve.

No aceite parcial de logs, só os logs válidos estão no stream OpenObserve
`logs`, com `productId` do token e `tenantId` do produto. No de traces, só os spans válidos estão no
stream OpenObserve de traces (padrão `traces`), também com `productId` do
token e `tenantId` do produto. Nenhum valor do lote substitui esses dois
campos.

O collector confirma o lote de logs ou de traces como aceito total só quando o OpenObserve responde HTTP 200,
`failed` é zero e `successful` é igual à quantidade enviada. Qualquer resposta
que não seja nem isso nem uma recusa de dado (acima) é destino indisponível. Credencial e URL
do OpenObserve não fazem parte deste contrato público: ficam em variável de
ambiente.

## gRPC

Porta padrão de produção: 4317. No perfil `local`: 4327.

| Serviço | Método |
|---|---|
| `opentelemetry.proto.collector.logs.v1.LogsService` | `Export` |
| `opentelemetry.proto.collector.trace.v1.TraceService` | `Export` |

Metadata `authorization: Bearer <token>`.

| Resultado | Status gRPC |
|---|---|
| Aceite total, lote vazio ou aceite parcial | `OK`, com o mesmo corpo protobuf do HTTP 200 |
| Não autorizado | `UNAUTHENTICATED` |
| Lote ou registro irrecuperável | `INVALID_ARGUMENT` |
| Acima do limite | `RESOURCE_EXHAUSTED`, sem `RetryInfo` (não é retry) |
| Destino ou catálogo de tokens fora | `UNAVAILABLE` |
| Métricas ou outro serviço | `UNIMPLEMENTED` |

Não há servidor de `MetricsService`.
