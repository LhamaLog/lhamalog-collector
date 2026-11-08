# Research: Publicar logs na fila de leitura (LLT-184)

## D1. Onde fica o retry e a DLQ

- **Decisão**: encapsular retry + escrita na DLQ em `AbstractStreamPublisher.publish` (método `final`), para
  logs e traces herdarem o mesmo comportamento sem duplicar código nos sinks.
- **Motivo**: FR-008; o 182 já centraliza o XADD ali. Os sinks continuam traduzindo só `MessagePublishException`
  → `SinkUnavailableException`.
- **Alternativas**: retry só em `PublishingLogsSink`/`PublishingTracesSink` (rejeitada: duplicação); outbox em
  Mongo (fora do escopo).

## D2. Nomes e limite das DLQs

- **Decisão**: `lhamalog:stream:logs:dlq` e `lhamalog:stream:traces:dlq` (domínio `logs:dlq` / `traces:dlq` via
  `StreamNames`), `MAXLEN ~ 100000`, override `LHAMALOG_MESSAGING_STREAM_LOGS_DLQ_MAX_LEN` /
  `LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN`. O corte aproximado pode descartar entradas mais antigas sem
  consumo; não garante retenção temporal nem durabilidade (D10).
- **Motivo**: clarificação Q1 e Q9; segue `lhamalog:stream:<domínio>` do ADR.
- **Alternativas**: uma DLQ única (rejeitada: mistura sinais e consumidores).

## D3. Política de retry

- **Decisão**: 3 tentativas totais no stream principal; backoff inicial 100 ms, multiplicador 2 e esperas de
  100 e 200 ms entre as tentativas. A espera de 400 ms só ocorreria se `maxAttempts=4`. O teto
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` (padrão 2000) limita cada espera e a soma das esperas
  (`min(backoff, teto − já esperado)`). Não há deadline da requisição nem do `XADD`. Variáveis:
  `LHAMALOG_MESSAGING_PUBLISH_MAX_ATTEMPTS`, `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_INITIAL_MS`,
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_MULTIPLIER`, `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS`. Valor
  inválido falha na subida (como o MAXLEN).
- **Motivo**: clarificação Q2 e Q6. O timeout de comando Redis (`REDIS_TIMEOUT`, padrão 2 s) é independente e
  não entra nesse teto; a spec não promete que a requisição caiba no timeout do exportador OTLP.
- **Semântica**: tentativa 1 sem espera; após falha, espera o backoff (limitado ao teto restante) antes da
  próxima. Esgotadas as tentativas → DLQ. Sucesso em qualquer tentativa → retorno normal, sem DLQ.
- **Alternativas**: retries infinitos (rejeitada); backoff só no cliente OTLP (já existe via 503; não registra
  falha local).

## D4. Payload da DLQ

- **Decisão**: campos do envelope original (`idempotencyId`, `event`, `origin`, `schemaVersion`, `publishedAt`,
  `metadata`, `body`) mais `targetStream`, `attempts`, `lastError`, `failedAt` (ISO UTC). `lastError` =
  `SimpleClassName + ": " + message` (ou só a classe se a mensagem for nula), depois da redação de padrões
  conhecidos: userinfo de URI (`esquema://usuario:segredo@`), rótulos `password`, `passwd`, `senha`, `secret`,
  `token`, `apikey`, `api_key` e `authorization`, e o prefixo `Bearer`. Isso não garante ausência de todo
  segredo arbitrário.
- **Motivo**: clarificações Q3 e Q7; o consumidor da DLQ reconstrói o destino e o motivo sem precisar de outro store.
- **Alternativas**: body JSON aninhado só com falha (rejeitada: perde o envelope “plano” do ADR).

## D5. Resposta ao exportador após DLQ

- **Decisão**: DLQ confirmada → `publish` **não** lança; o sink confirma sucesso → HTTP 200 / gRPC OK. Esse
  sucesso é a confirmação da escrita na DLQ, não o processamento final. DLQ falhou e o processo responde →
  `MessagePublishException` → `DestinationUnavailable` → 503 `Retry-After: 5` / `UNAVAILABLE`. Principal e DLQ
  usam a mesma instância Redis; os dois indisponíveis caem nesse 503 somente se o processo responder.
- **Motivo**: clarificações Q4 e Q8; o registro na DLQ evita perda silenciosa quando o stream principal falha
  e a DLQ ainda aceita escrita. Não promete que o lote já foi consumido.
- **Risco**: o consumidor principal não vê o lote até alguém reprocessar a DLQ (fora do escopo; task futura).
  Morte do processo antes da resposta não garante 503. Aceito como limite, sem mudança de código nesta rodada.

## D6. Escopo de sinais

- **Decisão**: logs e traces (clarificação Q5). Métricas fora.

## D7. Testes

- Unitário com Redis mock/Mockito ou template que falha N vezes: sucesso na 2ª tentativa; DLQ após 3 falhas
  (publish retorna ok e DLQ tem a entrada); falha principal + falha DLQ → `MessagePublishException`.
- Integração Testcontainers: DLQ recebe campos esperados; `MAXLEN` da DLQ configurável.
- Sinks: publisher que lança → ainda `DestinationUnavailable`; caminho traces espelhado.
- Backoff injetável (`Sleeper`) para não dormir de verdade nos testes.

## D8. Core e specs alheios

- **Decisão**: zero mudança no LhamaLog Core. Não editar `specs/001-*` nem `specs/003-*`. Não editar
  `infra/local/.env` (só `.env.example`).

## D9. Observabilidade

- Log em nível WARN após cada falha de tentativa (stream, attempt, erro com a mesma redação de padrões
  conhecidos de D4); INFO quando sucesso após retry > 1; ERROR quando envia à DLQ e quando a DLQ falha. Sem
  corpo de telemetria. A redação não garante ausência de todo segredo arbitrário.
- Métricas não fazem parte desta entrega; permanecem na task futura T016.

## D10. Limites que a implementação não promete

- **Decisão**: documentar os limites abaixo e deixar a definição operacional em aberto. Não escolher dono,
  retenção, persistência nem alertas nesta entrega. Não acrescentar deadline de requisição, regex novo nem teste
  novo.
- **Backoff**: o teto de 2000 ms vale só para as esperas entre tentativas. Não é deadline da requisição nem do
  `XADD`.
- **Redis único**: DLQ e stream principal compartilham a instância. Indisponibilidade dos dois, com processo
  vivo, → 503 `Retry-After: 5` / gRPC `UNAVAILABLE`.
- **`MAXLEN ~`**: pode descartar entradas mais antigas sem consumo. Não garante retenção temporal nem
  durabilidade. Dono, retenção, AOF/RDB, monitoramento e alertas da DLQ seguem sem definição.
- **Timeout ambíguo**: pode deixar referência no principal e na DLQ. O consumidor deve deduplicar por
  `idempotencyId` = `batchId`. Essa deduplicação no consumidor não foi verificada.
- **Interrupção e shutdown**: comportamento a validar na T019, inclusive a interação com o backoff, a DLQ e
  a resposta ao exportador. Esta entrega não promete resultado.
- **Eventos**: `log.ingested` e `trace.ingested` permanecem v1.