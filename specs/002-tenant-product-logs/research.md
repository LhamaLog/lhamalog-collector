# Research: Associar logs ao tenant e ao produto (LLT-182)

**Responsável**: michelbocchijr@gmail.com

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
- XADD um por lote (revisão D9: a entrada é a referência do lote), com `MAXLEN ~ 100000`; override por
  `LHAMALOG_MESSAGING_STREAM_<DOMINIO>_MAX_LEN` (`LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` e
  `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN`). Valor
  inválido derruba a subida.
- Campos da entrada: `idempotencyId`, `event`, `origin`, `schemaVersion`, `publishedAt` (ISO UTC),
  `metadata` (JSON) e `body` (JSON).

## D7. Senha do Redis local

- **Decisão**: `--requirepass ${REDIS_PASSWORD:?...}` no compose; healthcheck com `REDISCLI_AUTH` no
  ambiente do contêiner; `REDIS_PASSWORD=` vazio no `.env.example`; `spring.data.redis.password` no perfil
  local. O `.env` real não é alterado pela feature.

## D8. Testes

- Unitários: ordem e semântica de falha (`PublishingLogsSinkTest`), envelope e id determinístico
  (`TelemetryIngestedPublisherTest`), limites (`MessagingPropertiesTest`).
- Integração com Redis real por Testcontainers (`StreamPublisherRedisTest`): campos, MAXLEN ~, Redis fora.
- Aceitação (`ExportLogsAcceptanceTest`): stream com tenant/produto do token e contexto OTel preservado;
  token recusado e lote inválido sem entrada no stream. Imagem `redis:8.2.10`, a mesma do compose.

## D9. Referência em vez do log completo (2026-11-02, Michel)

- O OpenObserve é a fonte do conteúdo; o stream leva só a referência. O Finder carrega o conteúdo pela
  referência e a Varredura organiza por prioridade, criticidade, tenant e dados OTel.
- A ingestão `_json` não devolve id por registro; a busca `POST /api/{org}/_search` exige `start_time` e
  `end_time` em microssegundos (https://openobserve.ai/docs/reference/api/search/search/). Por isso cada
  documento ganha `lhamalog_batch_id` e `lhamalog_record_id`, e a referência leva a faixa de `_timestamp`.
- Uma referência por lote; lote de um registro leva `recordId` e aponta direto para ele.
- `lhamalog_record_id` é o antigo `idempotencyId` por log (D5). O `batchId` é UUID v3 sobre o sinal e os ids
  dos registros, e é o `idempotencyId` do evento: o reenvio do cliente gera o mesmo id.
- Trade-off: o reenvio após 503 regrava o OpenObserve com os mesmos ids; o Finder deve deduplicar por
  `lhamalog_record_id`.

## D10. Nomes de evento e streams

- Primeiro consumo (collector): `log.ingested` (mantém o nome; `schemaVersion` sobe para 2 por mudança
  incompatível do corpo) e `trace.ingested` (versão 1). O sinal no nome deixa o tipo claro sem ler o corpo.
- Depois da Varredura (varredura de dados sensíveis e validação de prompt-injection): `log.checked` e
  `trace.checked`, publicados fora do collector; só documentado aqui.
- **Dois streams**: `lhamalog:stream:logs` e `lhamalog:stream:traces`. Segue a regra
  `lhamalog:stream:<domínio>` do ADR; consumidores, `MAXLEN` (`LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN`) e
  vazão independentes, e um pico de traces não atrasa logs.

## D11. Prioridade e criticidade

Só pela severidade (Michel). `severityNumber` 21-24 FATAL/CRITICAL, 17-20 ERROR/HIGH, 13-16 WARN/MEDIUM,
9-12 INFO/LOW, 5-8 DEBUG/LOW, 1-4 TRACE/LOW; 0 usa o prefixo de `severityText`; sem nada, UNSPECIFIED/LOW.
O lote recebe o máximo e `severityCounts` por nível. Spans não têm severidade: UNSPECIFIED/LOW.

## D12. Bytes

`bytes` é a soma do tamanho protobuf serializado dos registros aceitos (sem resource e scope).

## D13. Lotes legítimos idênticos colidem (revisão do PR #3, 2026-11-04)

- **Decisão**: dois envios com bytes idênticos (mesmo token, recurso, escopo e registros, inclusive
  `timeUnixNano`) geram o mesmo `batchId` e os mesmos `lhamalog_record_id`, e a Varredura trata o segundo
  como reenvio. É intencional: o collector não distingue um reenvio do cliente de um lote idêntico legítimo.
- **Motivo**: deduplicação no reenvio após 503 vale mais; lotes reais quase sempre diferem no horário.
- **Alternativas**: id aleatório por requisição (quebra a deduplicação); id do cliente (OTLP não tem).

## D14. Pontos do contrato da referência

- `correlationId` é aleatório por chamada de exportação: não é estável entre reenvios. Para deduplicar use
  `idempotencyId` (= `batchId`).
- `bytes` conta só os registros serializados (sem resource e scope), como em D12.
- O Finder não limita a busca a `size = recordCount`: um reenvio após 503 pode deixar cópias com o mesmo
  `lhamalog_record_id`. Ele pagina sem esse limite e deduplica por `lhamalog_record_id` antes de contar.

## D15. Severidades fora do comum (revisão do PR #3)

- `severityNumber` de 1 a 24 manda; o texto só é lido com número 0 (ou negativo). Número e texto
  conflitantes: vale o número (ex.: 9 + `ERROR` é INFO).
- Número acima de 24 (fora do OTel, ex.: 25) conta como FATAL/CRITICAL, para não rebaixar algo que o
  cliente marcou como mais grave que o máximo.
- Texto (sem diferenciar maiúsculas, prefixo, espaços removidos): `FATAL`, `CRIT`, `EMERG`, `ALERT` são
  FATAL; `ERR` é ERROR; `WARN` é WARN; `INFO` e `NOTICE` são INFO; `DEBUG` é DEBUG; `TRACE` é TRACE; o resto
  UNSPECIFIED.

## D16. Lote vazio

- `store()` volta sem chamar o OpenObserve quando o lote não tem registros aceitos, e nada é publicado.
