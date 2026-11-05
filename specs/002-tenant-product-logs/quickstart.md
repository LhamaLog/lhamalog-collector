# Quickstart: Associar logs ao tenant e ao produto

Roteiro para provar a feature. Cenários em [spec.md](spec.md); formato do evento em
[contracts/log-ingested-stream.md](contracts/log-ingested-stream.md).

## Pré-requisitos

- JDK 25, Docker e o Maven Wrapper do repositório
- `infra/local/.env` com as variáveis da feature 001 e agora `REDIS_PASSWORD` (o compose para com erro se
  faltar). Defina a senha só no `.env`, nunca em arquivo versionado.

## Testes automatizados

```powershell
.\mvnw.cmd clean verify
```

O que precisa passar:

1. Lote aceito por HTTP e gRPC: documentos no OpenObserve e uma entrada por lote em `lhamalog:stream:logs`
   (ou `lhamalog:stream:traces`) com `tenantId` e `productId` do token nos metadados, mesmo com atributos
   falsos no lote.
2. `service.name`, atributos de recurso, escopo e atributos do log preservados no OpenObserve; o corpo do
   evento leva só a referência (`batchId`, faixa de `_timestamp`), sem conteúdo.
3. Token recusado e lote todo inválido: nenhuma entrada no stream.
4. OpenObserve fora ou recusando: nada publicado.
5. Redis fora: 503 / `UNAVAILABLE`, sem sucesso.
6. Mesmo lote reenviado: mesmo `batchId`/`idempotencyId` no stream e mesmo `lhamalog_record_id` no
   OpenObserve; logs idênticos no lote: ids de registro distintos.
7. `MAXLEN ~` aplicado e override por `LHAMALOG_MESSAGING_STREAM_<DOMINIO>_MAX_LEN` (`LOGS` e `TRACES`).

## Ensaio manual no perfil local

```powershell
docker compose -f infra/local/compose.yaml up -d
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:REDIS_PASSWORD = '<a mesma senha do infra/local/.env>'
.\mvnw.cmd spring-boot:run
```

Cadastre produto e token como no quickstart da feature 001 e envie um log para `http://localhost:4328/v1/logs`.
Depois leia o stream (a senha vem do ambiente do contêiner):

```powershell
docker exec lhamalog-collector-redis redis-cli XRANGE lhamalog:stream:logs - + COUNT 5
```

Esperado: uma entrada por lote aceito, `metadata` com o seu `tenantId` e `productId` e `body` com a
referência ao OpenObserve. Pare o Redis
(`docker stop lhamalog-collector-redis`), envie de novo e confira 503 com `Retry-After`.
