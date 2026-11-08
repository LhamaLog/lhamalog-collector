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
  `LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN`.
- **Motivo**: clarificação Q1; segue `lhamalog:stream:<domínio>` do ADR.
- **Alternativas**: uma DLQ única (rejeitada: mistura sinais e consumidores).

## D3. Política de retry

- **Decisão**: 3 tentativas totais no stream principal; backoff inicial 100 ms, multiplicador 2 e esperas de
  100 e 200 ms entre as tentativas. A espera de 400 ms só ocorreria se `maxAttempts=4`; teto de espera
  acumulada ~2000 ms. Variáveis:
  `LHAMALOG_MESSAGING_PUBLISH_MAX_ATTEMPTS`, `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_INITIAL_MS`,
  `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_MULTIPLIER`, `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS`. Valor
  inválido falha na subida (como o MAXLEN).
- **Motivo**: clarificação Q2; cabe no timeout típico do exportador OTLP (10 s) e no timeout Redis de 2 s por
  comando.
- **Semântica**: tentativa 1 sem espera; após falha, espera o backoff (limitado ao teto restante) antes da
  próxima. Esgotadas as tentativas → DLQ. Sucesso em qualquer tentativa → retorno normal, sem DLQ.
- **Alternativas**: retries infinitos (rejeitada); backoff só no cliente OTLP (já existe via 503; não registra
  falha local).

## D4. Payload da DLQ

- **Decisão**: campos do envelope original (`idempotencyId`, `event`, `origin`, `schemaVersion`, `publishedAt`,
  `metadata`, `body`) mais `targetStream`, `attempts`, `lastError`, `failedAt` (ISO UTC). `lastError` =
  `SimpleClassName + ": " + message` (ou só a classe se a mensagem for nula); nunca senha, token ou URI com
  credencial.
- **Motivo**: clarificação Q3; o consumidor da DLQ reconstrói o destino e o motivo sem precisar de outro store.
- **Alternativas**: body JSON aninhado só com falha (rejeitada: perde o envelope “plano” do ADR).

## D5. Resposta ao exportador após DLQ

- **Decisão**: DLQ confirmada → `publish` **não** lança; o sink confirma sucesso → HTTP 200 / gRPC OK.
  DLQ falhou → `MessagePublishException` → `DestinationUnavailable` → 503 `Retry-After: 5` / `UNAVAILABLE`.
- **Motivo**: clarificação Q4; at-least-once com registro local (DLQ) evita perda silenciosa e reduz reenvio
  inútil quando o Redis principal está degradado mas a DLQ ainda aceita escrita.
- **Risco**: o consumidor principal não vê o lote até alguém reprocessar a DLQ (fora do escopo). Aceito.

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

- Log em nível WARN após cada falha de tentativa (stream, attempt, erro sanitizado); INFO quando sucesso após
  retry > 1; ERROR quando envia à DLQ e quando a DLQ falha. Sem corpo de telemetria nem segredo.
- Métricas não fazem parte desta entrega; permanecem na task futura T016.