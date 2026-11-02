# Research: Associar logs ao tenant e ao produto (LLT-182)

## D1. Origem de `tenantId` e `productId`

- **Decisão**: manter a regra do LLT-181: os dois vêm só do token validado (`TokenValidationResult.Valid`).
  O LLT-182 reforça com testes no OpenObserve e na fila.
- **Motivo**: o token é a única prova de identidade; atributos do lote são dados do cliente.
- **Alternativas**: ler do recurso OTel (rejeitada: permite se passar por outro tenant).

## D2. Fila e nome do stream

- **Decisão**: Redis Streams, stream `lhamalog:stream:logs` (domínio `logs`), evento `log.ingested`.
- **Motivo**: segue o rascunho de ADR (`lhamalog:stream:<dominio>`). O domínio `logs` deixa `traces` livre
  para um stream próprio depois. `ingestion` misturaria os dois.
- **Alternativas**: `lhamalog:stream:ingestion` (rejeitada pelo motivo acima).

## D3. Ordem: OpenObserve primeiro, depois Redis

- **Decisão**: decorador `PublishingLogsSink` sobre `OpenObserveLogsSink`. Grava no OpenObserve; só com a
  gravação confirmada publica no Redis.
- **Motivo**: a Varredura não deve receber evento de log que não está guardado; a confirmação ao cliente só
  sai depois dos dois.
- **Alternativas**: Redis primeiro (evento de log possivelmente não guardado); em paralelo (dois estados de
  falha difíceis de explicar); outbox (exige banco transacional e mais infraestrutura, fora do escopo).

## D4. Semântica de falha

| Situação | Resposta ao cliente | Publica? |
|---|---|---|
| Sem token / token inválido / catálogo fora | 401 / 503 (como no LLT-181) | Não |
| Todos os registros inválidos | 400 / `INVALID_ARGUMENT` | Não |
| OpenObserve indisponível | 503 / `UNAVAILABLE` + Retry-After | Não |
| OpenObserve recusa algum registro | 400 ou `partial_success` | Não |
| Redis indisponível ou sem confirmar todas as entradas | 503 / `UNAVAILABLE`, Retry-After 5 s | Parcial possível |
| Tudo confirmado | 200 / OK (ou `partial_success` dos inválidos) | Sim |

- Falha do Redis vira `SinkUnavailableException`, que o `BatchIngestion` já traduz para
  `DestinationUnavailable`.
- No reenvio o OpenObserve recebe o lote de novo (entrega pelo menos uma vez, duplicata possível no
  OpenObserve) e o Redis recebe os mesmos `idempotencyId`; a Varredura descarta repetidos (SET NX, TTL 25 h,
  conforme o ADR).
- Recusa parcial do OpenObserve: a resposta dele não diz quais registros foram guardados, então nada é
  publicado. Risco: logs guardados sem evento na Varredura. Aceito nesta versão e registrado como risco.
- Timeout de comando e de conexão do Redis: 2 s (`REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`), para a falha
  virar 503 rápido.

## D5. `idempotencyId` determinístico

- **Decisão**: UUID baseado em nome (`UUID.nameUUIDFromBytes`) sobre `tenantId`, `productId`, bytes do
  recurso, do escopo e do registro (cada parte prefixada pelo tamanho), mais o número da ocorrência de um
  registro idêntico no lote.
- **Motivo**: o reenvio do mesmo lote gera os mesmos ids; dois logs idênticos no mesmo lote não colidem; a
  posição no lote não entra, então o descarte de um registro que saiu da janela de horário num reenvio não
  muda o id dos outros.
- **Alternativas**: UUID aleatório (quebra a deduplicação no reenvio); índice no lote (muda com a validação).
- `correlationId`: UUID aleatório por chamada de exportação, para agrupar os eventos de um mesmo envio.

## D6. Reuso da POC

- **Decisão**: trazer para `br.com.lhamalog.collector.messaging` o núcleo da POC
  (`AbstractStreamPublisher<T>` com `publish` final, `MessageEnvelope`, `MessagingProperties`, `MetadataKeys`,
  `StreamNames`), só o lado que publica. `publish` recebe `OutboundMessage<T>` com o id já calculado.
- XADD em pipeline, um por log, com `MAXLEN ~ 100000`; override por
  `LHAMALOG_MESSAGING_STREAM_<DOMINIO>_MAX_LEN` (aqui `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN`). Valor
  inválido derruba a subida.
- Campos da entrada: `idempotencyId`, `event`, `origin`, `schemaVersion`, `publishedAt` (ISO UTC),
  `metadata` (JSON) e `body` (JSON).

## D7. Senha do Redis local

- **Decisão**: `--requirepass ${REDIS_PASSWORD:?...}` no compose; healthcheck com `REDISCLI_AUTH` no
  ambiente do contêiner; `REDIS_PASSWORD=` vazio no `.env.example`; `spring.data.redis.password` no perfil
  local. O `.env` real não é alterado pela feature.

## D8. Testes

- Unitários: ordem e semântica de falha (`PublishingLogsSinkTest`), envelope e id determinístico
  (`LogIngestedPublisherTest`), limites (`MessagingPropertiesTest`).
- Integração com Redis real por Testcontainers (`StreamPublisherRedisTest`): campos, MAXLEN ~, Redis fora.
- Aceitação (`ExportLogsAcceptanceTest`): stream com tenant/produto do token e contexto OTel preservado;
  token recusado e lote inválido sem entrada no stream. Imagem `redis:8.2.10`, a mesma do compose.
