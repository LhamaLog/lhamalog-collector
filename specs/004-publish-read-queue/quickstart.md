# Quickstart: Publicar logs na fila de leitura (LLT-184)

Roteiro para provar retry interno e DLQ. Cenários em [spec.md](spec.md); formato em
[contracts/dlq-stream.md](contracts/dlq-stream.md).

## Pré-requisitos

- JDK 25, Docker e o Maven Wrapper do repositório
- `infra/local/.env` já configurado (não editar neste roteiro; use só `.env.example` como referência de
  novas variáveis)

## Testes automatizados

```powershell
.\mvnw.cmd clean verify
```

O que precisa passar além do 182:

1. Falha transitória no XADD: retry interno recupera; cliente recebe sucesso; DLQ vazia.
2. Três falhas no principal + DLQ ok: entrada na DLQ com envelope, `targetStream`, `attempts`, `lastError`,
   `failedAt`; o publish conclui e o sink trata como 200 / OK. Esse 200 / OK confirma a DLQ e não é
   processamento final. `lastError` redige padrões conhecidos e não promete ausência de todo segredo.
3. Principal e DLQ fora, com o processo respondendo: 503 `Retry-After: 5` / `UNAVAILABLE` (HTTP e gRPC), logs
   e traces. Os dois streams estão na mesma instância Redis. Morte do processo não garante 503.
4. Overrides `LHAMALOG_MESSAGING_PUBLISH_*` e `LHAMALOG_MESSAGING_STREAM_*_DLQ_MAX_LEN` lidos; valor inválido
   falha na subida. O teto `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` (padrão 2000) limita as esperas de
   backoff (padrão 100 ms e 200 ms), não a requisição nem o `XADD`.

## Ensaio manual no perfil local

Suba o stack e a app como no quickstart do 002. Com Redis saudável, envie um log e confira o stream
principal. Para forçar DLQ em ensaio controlado, use os testes automatizados (falha injetada); derrubar o
Redis inteiro, com o processo ainda respondendo, resulta em 503 (DLQ no mesmo Redis).

## Fora deste roteiro

Não fazem parte da verificação atual e permanecem tasks abertas em `tasks.md`: política operacional da DLQ
(dono, retenção, persistência, alertas e efeito do `MAXLEN ~`), aceitação E2E HTTP/gRPC com DLQ confirmada,
teste de interrupção/shutdown, teste de duplicação principal+DLQ em timeout ambíguo, reprocessador da DLQ e
atualização do Jira LLT-184. `log.ingested` e `trace.ingested` permanecem v1. Métricas continuam na T016.

```powershell
docker exec lhamalog-collector-redis redis-cli XRANGE lhamalog:stream:logs:dlq - + COUNT 5
```