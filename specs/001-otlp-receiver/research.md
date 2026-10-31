# Research: Recepção OTLP de logs e traces

Decisões para implementar a spec em Java 25 e Spring Boot 4.1.1, com HTTP e gRPC.
Nenhum item ficou em aberto.

## 1. Stack

**Decision**: Java 25, Spring Boot 4.1.1, Maven Wrapper. Código novo no pacote
`br.com.lhamalog.collector`.

**Rationale**: É o que o pedido de plano e a constituição fixam. O `pom.xml` já
usa esse parent e `java.version` 25.

**Alternatives considered**: Outra linguagem ou outro framework HTTP. Foram
descartados porque a constituição torna o stack obrigatório.

## 2. Bibliotecas do receptor

**Decision**:

- `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha` (Maven Central, 2026-09-29)
- BOM `io.grpc:grpc-bom:1.85.1` e os artefatos `grpc-netty-shaded`, `grpc-protobuf`
  e `grpc-services`, sem versão própria
- `protobuf-java-util` alinhado ao BOM do gRPC, para o JSON do OTLP
- `com.github.ben-manes.caffeine:caffeine`, sem versão, deixando o parent do
  Spring Boot gerenciar

**Rationale**: O README já lista proto OTLP, gRPC shaded e Caffeine como
dependências a acrescentar na mão. As versões acima são as publicadas no
Maven Central na data deste plano. O servidor gRPC usa o Netty shaded para não
brigar com o servidor HTTP da aplicação.

**Alternatives considered**: Um starter gRPC de terceiros. Foi descartado porque
o README nomeia os artefatos `io.grpc` diretos e o token do produto não é JWT:
não passa pelo resource server OAuth2 que já está no classpath.

## 3. Dois servidores, fora da porta 8080

**Decision**: O receptor HTTP OTLP e o servidor gRPC sobem no ciclo de vida do
Spring, em portas próprias. A aplicação continua na porta 8080 (actuator e o
restante). O filtro do Spring Security não autentica esses dois canais.

**Rationale**: O token de produto é opaco. O starter OAuth2 resource server
espera JWT na porta 8080. Misturar os dois no mesmo conector recusaria um
Bearer válido de produto ou aceitaria um JWT no lugar do token do produto.
A spec OTLP também separa a porta do receptor (4317 gRPC, 4318 HTTP) da
aplicação.

**Alternatives considered**: `POST /v1/logs` no Spring MVC da porta 8080.
Descartado pelo conflito com o Spring Security e com as portas padrão do OTLP.

## 4. Portas no perfil local

**Decision**:

| Perfil | HTTP OTLP | gRPC OTLP |
|---|---|---|
| `prod` | 4318 | 4317 |
| `local` | 4328 | 4327 |

Valores configuráveis por `lhamalog.otlp.http.port` e `lhamalog.otlp.grpc.port`.
Cada canal pode ser desligado.

**Rationale**: `infra/local/compose.yaml` já publica o OpenTelemetry Collector
de observabilidade da própria aplicação em 4317 e 4318. Subir o receptor nessas
portas no perfil local impede `make infra-up` e a aplicação ao mesmo tempo.
Em produção o receptor ocupa as portas padrão do protocolo.

**Alternatives considered**: Manter 4317/4318 também no local, como no
protótipo revertido (`ffa3ac5`). Descarta o compose atual.

## 5. Destino de cada sinal

**Decision**:

- Logs aceitos vão para o OpenObserve, `POST /api/{organization}/{stream}/_json`,
  organização `lhamalog` e stream `logs` (os dois configuráveis). Cada registro
  aceito é um objeto JSON do array. A confirmação é HTTP 200 com `failed` igual
  a zero e `successful` igual à quantidade enviada.
- Traces aceitos continuam no Redis Stream `lhamalog:traces`. A confirmação é o
  ack do `XADD` de cada span aceito.
- O campo de produto nos dois destinos é o `productId` do token. Atributos
  internos do lote não substituem esse campo.
- Sem outbox e sem escrita dupla. Falha do destino daquele sinal responde
  indisponível. Reenvio pode duplicar (a spec admite isso).
- A credencial do OpenObserve (usuário e senha, ou token de ingestão no lugar
  da senha) vem de variável de ambiente. Não entra no Git. Basic auth, como a
  API do OpenObserve exige.
- O scaffold atual ainda depende do Elasticsearch (pom, compose, properties e
  Testcontainers). A implementação remove esse caminho. O compose local ganha
  o OpenObserve OSS (`public.ecr.aws/zinclabs/openobserve`, porta 5080), com a
  mesma tag no compose e no teste.

**Rationale**: O time troca o armazenamento de logs do Elasticsearch para o
OpenObserve. A spec só exige um destino que confirme o recebimento; o
OpenObserve faz isso no JSON de ingestão (`code` 200 e a contagem `successful`
/ `failed`). Traces não usavam Elasticsearch, então seguem no Redis. Se o
OpenObserve aceita só parte do array, o collector responde indisponível: a
spec não confirma sucesso enquanto faltar registro aceito.

**Alternatives considered**:

- Manter o Elasticsearch para logs. O time decidiu encerrar esse uso.
- Reenviar o OTLP bruto ao endpoint OTLP do OpenObserve. O `productId` do token
  precisa existir como campo do log; o JSON de ingestão carrega esse campo sem
  depender do achatamento do OTLP.
- Mandar traces também ao OpenObserve. Não foi pedido. O Redis continua o
  destino de traces.
- Tratar `failed` maior que zero como sucesso parcial OTLP. O OpenObserve não
  diz qual registro falhou, e a spec só confirma o que o destino recebeu por
  inteiro.

O stream não usa `MAXLEN` nesta feature. Quem consome o stream é que limita o
tamanho. Cortar no collector apagaria um trace já confirmado.

## 6. Token que identifica o produto

**Decision**: O collector só lê. A coleção MongoDB `products` guarda
`productId`, `tokenHash` (SHA-256 em hex dos bytes UTF-8 do token), `status`
(`ACTIVE` ou `REVOKED`) e `expiresAt` opcional. O token apresentado é
hasheado e buscado. Caffeine guarda o resultado por 30 segundos, inclusive a
ausência. Valor de token não entra em código, teste commitado, documentação
nem log.

Cadastro, rotação e tela de produto continuam fora desta feature. Teste e
quickstart inserem o documento com um token gerado na hora.

Se o MongoDB não responder, a resposta é indisponível (o collector não sabe se
o token vale). Token ausente, vazio, desconhecido, revogado ou expirado é uma
única recusa de não autorizado, sem detalhar o motivo ao remetente.

**Rationale**: A spec deixa o cadastro fora e exige token válido ligado a um
produto. O protótipo lia a lista de tokens em propriedade, com o comentário de
que isso era provisório até o MongoDB. O MongoDB já é dependência. Hash evita
guardar o segredo em claro. O TTL curto faz revogação aparecer sem transformar
o cache em fonte permanente.

**Alternatives considered**:

- Lista de tokens em `application-*.properties`. Coloca segredo em arquivo e
  não identifica um documento de produto.
- JWT no resource server. O produto não emite JWT neste contrato.
- Cache só de acertos. Um token recém-revogado continuaria válido até o
  processo reiniciar.

## 7. Mapa de respostas

**Decision**: Os dois canais chamam o mesmo caso de uso. A classe do resultado
é a mesma; muda só o transporte. Mensagens de `Status` e de `partial_success`
ficam em inglês, como pede a spec OTLP.

| Situação | HTTP | gRPC | Corpo |
|---|---|---|---|
| Lote válido, destino confirmou | 200 | OK | `Export*ServiceResponse` sem `partial_success` |
| Lote vazio bem formado | 200 | OK | resposta vazia; destino não é chamado |
| Parte válida e parte inválida, destino confirmou os válidos | 200 | OK | `partial_success` com a contagem e `error_message` |
| Corpo ilegível, sinal errado no canal, ou todos os registros inválidos | 400 | `INVALID_ARGUMENT` | `google.rpc.Status` |
| Sem Bearer válido | 401 | `UNAUTHENTICATED` | `Status`; HTTP inclui `WWW-Authenticate: Bearer` |
| Corpo acima do limite, depois da descompressão | 413 | `RESOURCE_EXHAUSTED` sem `RetryInfo` | `Status` |
| Destino ou catálogo de tokens indisponível | 503 | `UNAVAILABLE` | `Status`; HTTP inclui `Retry-After` em segundos |
| Outro caminho ou métricas | 404 | serviço de métricas não registrado (`UNIMPLEMENTED`) | `Status` |

Autenticação ocorre antes de tratar o lote. Destino indisponível no meio do
lote válido não vira sucesso parcial: a spec OTLP não tem como pedir retry só
de uma parte, e a nossa spec não confirma sucesso sem o destino.

Quando todos os registros são inválidos, a resposta é 400 e o destino não é
chamado. 200 com `rejected_*` igual ao total seria sucesso parcial para o
cliente OTLP, e a spec desta feature não conta isso como sucesso.

**Rationale**: Tabela alinhada à spec OTLP/HTTP e OTLP/gRPC (sucesso 200,
dado inválido permanente 400, corpo grande 413, backpressure 503 com retry).
`UNAUTHENTICATED` e `INVALID_ARGUMENT` não são retryable; `UNAVAILABLE` é.

**Alternatives considered**: 200 com todos os registros em `partial_success`
quando nada é válido. O cliente OTLP não reenvia e trata como aceite parcial.
Contraria FR-007.

## 8. O que torna um registro inválido

**Decision**:

Log inválido quando:

- `trace_id` presente e com tamanho diferente de 16 bytes
- `span_id` presente e com tamanho diferente de 8 bytes
- `severity_number` fora de 0..24

Span inválido quando:

- `trace_id` ausente, com tamanho diferente de 16 bytes, ou só zeros
- `span_id` ausente, com tamanho diferente de 8 bytes, ou só zeros
- `parent_span_id` presente e com tamanho diferente de 8 bytes
- `name` vazio
- `end_time_unix_nano` diferente de zero e anterior a `start_time_unix_nano`

Um log sem body continua válido. Essas regras são lógica de negócio e ficam
com o desenvolvedor (regra 60/40).

**Rationale**: É o mínimo que distingue lixo estrutural de telemetria aceitável,
sem exigir campos que o OTLP trata como opcionais no log. O protótipo revertido
já usava esse corte; a spec pede rejeição por registro.

**Alternatives considered**: Exigir body no log, ou aceitar span sem nome.
Aumenta ou afrouxa a spec sem necessidade.

## 9. JSON do OTLP

**Decision**: O mesmo porto HTTP aceita `application/x-protobuf` e
`application/json`, e `Content-Encoding: gzip`. No JSON, `traceId` e `spanId`
são hex, enums são inteiros. O `JsonFormat` padrão do Protobuf, que codifica
bytes em base64, não serve sozinho para esses campos.

**Rationale**: A spec OTLP manda o servidor aceitar os dois encodings na mesma
porta e desviar do JSON padrão do Protobuf nos identificadores.

**Alternatives considered**: Só protobuf binário. SDKs e o `curl` de diagnóstico
mandam JSON. Recusar JSON quebra o canal HTTP que a spec pede.

## 10. Tamanho máximo

**Decision**: Limite padrão de 64 MiB no corpo já descomprimido (HTTP) e na
mensagem gRPC. Propriedade configurável, igual nos dois canais. Acima do limite,
rejeição do envio inteiro.

**Rationale**: É o padrão recomendado pela spec OTLP. O protótipo usava 4 MiB,
abaixo da recomendação do protocolo.

**Alternatives considered**: 4 MiB fixos. Protege mais a memória e rejeita
lotes que um cliente OTLP padrão ainda enviaria.

## 11. Autoria 60/40

**Decision**: O desenvolvedor escreve a lógica de negócio: validade do
registro, atribuição ao produto do token, lote vazio, aceite parcial, rejeição
total e a escolha da classe de erro quando o destino falha. A IA pode escrever
o arranque dos servidores, o enquadramento protobuf/gzip e os adaptadores de
OpenObserve, Redis e MongoDB, desde que a parcela do desenvolvedor cubra
essas regras e fique em pelo menos 40% do código da feature. As tasks marcarão
`[Michel]` e `[IA]`.

**Rationale**: Constituição, princípio II. O protótipo foi revertido justamente
por ter sido escrito inteiro pela IA.

**Alternatives considered**: Repartir por camada (IA nos adaptadores, pessoa no
domínio) sem olhar o percentual. A constituição exige os dois: marca na task e
mínimo de 40%, com a lógica de negócio na parte humana.
