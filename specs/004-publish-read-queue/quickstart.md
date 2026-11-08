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
   `failedAt`; resposta 200 / OK.
3. Principal e DLQ fora: 503 / `UNAVAILABLE` (HTTP e gRPC), logs e traces.
4. Overrides `LHAMALOG_MESSAGING_PUBLISH_*` e `LHAMALOG_MESSAGING_STREAM_*_DLQ_MAX_LEN` lidos; valor inválido
   falha na subida.

## Ensaio manual no perfil local

Suba o stack e a app como no quickstart do 002. Com Redis saudável, envie um log e confira o stream
principal. Para forçar DLQ em ensaio controlado, use os testes automatizados (falha injetada); derrubar o
Redis inteiro ainda resulta em 503 (DLQ no mesmo Redis).

```powershell
docker exec lhamalog-collector-redis redis-cli XRANGE lhamalog:stream:logs:dlq - + COUNT 5
```