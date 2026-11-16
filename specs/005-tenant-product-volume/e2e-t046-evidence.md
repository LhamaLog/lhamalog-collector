# Evidencias T046 (ensaio E2E LLT-186)

Data: 2026-11-10 21:18:00 America/Sao_Paulo

Nenhum segredo, token ou senha foi registrado neste arquivo. Tokens so aparecem como length + SHA-256.

## Diagnostico do HTTP 401 anterior

No ensaio anterior o probe POST com o token do script recebia 201, mas a outbox ficava
`ACCEPTED` + `lastErrorCode=HTTP_401` e `nextAttemptAt: Invalid Date`. Os ticks do worker
iam a `ready=0` e o Core nao recebia fatos.

Comparacao byte a byte (ensaio limpo de 2026-11-10 21:17 America/Sao_Paulo), sem imprimir o token:

- Header de autenticacao: `X-LhamaLog-Service-Token` (mesmo nome do probe).
- Valor: length=64, SHA-256 `67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195`
  identico no script, no `CoreAuthenticationProvider` do Collector e no filtro do Core.
- `Authorization`: ausente no cliente (`authorizationPresent=false`). O filtro do Core
  rejeitaria com `authorization-header-present` se viesse preenchido.
- Path: `POST http://127.0.0.1:8081/internal/usage-facts`.
- `Content-Type`: `application/json`.
- Motivo no filtro do Core: nenhuma linha `Rejected` (os POSTs do worker voltaram 201).
- Logs do Core desta vez em `llt186-core-run-1.log` e `llt186-core-run-2.log` (nao se sobrescrevem).

Causa mais provavel do 401 original: o valor visto pelo JVM do Collector nao era o mesmo do
probe/Core (mesmo tipo de falha do `usage.enabled` no fork do Maven), ou o valor do env
tinha espaco/CRLF/aspas. Sem fingerprint nao deu para cravar o motivo naquele log, e o
restart do Core sobrescrevia o arquivo. Correcao: `strip` + aspas no carregamento do token
(Collector e Core), fallback para `System.getenv("LHAMALOG_COLLECTOR_CORE_TOKEN")` se o
`@Value` vier vazio, e log de length/SHA-256 dos dois lados.

O ensaio imediatamente anterior a esta correcao ainda mostrava HTTP_401 + Invalid Date, mas
era volume persistente do compose (`usage_outbox` de uma tentativa antiga). O worker nao
chegava a POST (ticks `ready=0`); o probe 201 e os fingerprints ja batiam. O seed agora
faz `usage_outbox.drop()` sem tocar em `infra/local/.env`.

## Diagnostico do Invalid Date

`nextAttemptAt: Invalid Date` no mongosh aparecia depois do `retry` (inclusive no caminho
antigo que tratava 401 como retry). Nao foi overflow de `Long.MAX_VALUE` no backoff atual
(ja havia teto de shift 20); o item com `attemptCount=1` ja nascia com data invalida.

Causa: `MongoUsageOutboxRepository.applyReplacement` fazia `Update.fromDocument($set)`
com o `Document` do converter. Esse `$set` gravava `nextAttemptAt` fora do intervalo de
BSON Date (mongosh mostra `Invalid Date`). `findAcceptedReady(nextAttemptAt <= agora)`
deixava de devolver o documento.

Correcao: `findAndReplace` do documento inteiro; `nextRetryAt` com teto de backoff e
teto BSON `253402300799999` (31/12/9999). 401/403 vao para `SUSPENDED` (sem data invalida).
Como retomar depois de corrigir o token: ver `docs/usage-measurement.md` (updateMany
`SUSPENDED` → `ACCEPTED` com `nextAttemptAt: new Date()`).

## Resultado do ensaio (Core confirmado)

- OTLP HTTP JSON: A1=200, reenvio A1=200, traces B=200, parcial A=200, lote novo A1+A2=200; gRPC logs A=ok; C com Core fora=200.
- Outbox apos ingest: 5 `SYNCED` (74, 95, 82, 98, 66), ISODate valida.
- Worker: headerNames=`[Content-Type, X-LhamaLog-Service-Token]`, respostas 201.
- Core GET A: 4 fatos de logs (74, 82, 98, 66). GET B: 1 fato de traces (95).
- current-cycle A: logs 5 registros / 320 bytes; traces 0. Ciclo `2026-11-01Z`–`2026-12-01Z`.
- current-cycle B: traces 1 / 95 bytes. Ciclo `2026-11-08Z`–`2026-11-15Z`.
- Reenvio identico de A1 nao duplicou. Lote novo A1+A2 entrou inteiro (2/98).
- Lote C apos Core voltar: 6 `SYNCED`; current-cycle A logs 6 / 399 bytes (`320+79`).
  Item C: `attemptCount=4`, `lastErrorCode=NETWORK`, `nextAttemptAt` ISODate valida.

## Comandos e resultados
comando: docker compose --env-file infra/local/.env -f infra/local/compose.yaml up -d
mongo collector health: healthy
comando: docker run -d --name lhamalog-llt186-e2e-core-mongo -p 27019:27017 mongo:8.0.34
seed collector: drop usage_outbox (volume persistente do compose anterior) + products/product_tokens
seed core: PlanSubscription firstPaymentDate=2026-11-01T00:00:00Z (UTC midnight; fonte externa permanece indefinida)
service token fingerprint: length=64 sha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195
compile: Core e Collector ok (sem testes)
comando: mvnw.cmd spring-boot:run (Core profile=local port 8081; log llt186-core-run-1.log)
comando: mvnw.cmd spring-boot:run (Collector profile=local; usage.enabled via env, JVM e run.arguments; token so no ambiente do processo)
health: Core 8081 OK; Collector 8080 OK
probe POST usage-facts: HTTP 201
collector startup usage: 2026-11-10T21:17:39.017-03:00  INFO 39536 --- [lhamalog-collector] [           main] [                                                 ] b.c.l.c.u.c.CoreAuthenticationProvider   : Collector Core service token configured length=64 sha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:39.160-03:00  INFO 39536 --- [lhamalog-collector] [           main] [                                                 ] b.c.l.c.usage.core.UsageOutboxWorker     : Usage outbox worker scheduled (interval=PT2S) || 2026-11-10T21:17:40.703-03:00  INFO 39536 --- [lhamalog-collector] [           main] [                                                 ] b.c.l.collector.config.StartupLogger     : [LhamaLog] lhamalog-collector iniciado | versao=0.0.1-SNAPSHOT | porta=8080 | perfis=local | java=25 | usage.enabled=true
core token fingerprint: 2026-11-10T21:17:39.125-03:00  INFO 12212 --- [lhamalog-core] [           main] [                                                 ] SecurityConfiguration$ServiceTokenFilter : Core usage token configured length=64 sha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195
OTLP HTTP JSON: logs A1=200, reenvio A1=200, traces B=200, lote parcial A=200, lote novo A1+A2=200
OTLP gRPC logs A: ok
outbox states apos ingest: test> [ { _id: 'SYNCED', n: 5 } ] [ { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('74') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:42.569Z') }, { fact: { domain: 'TRACES', acceptedRecordCount: 1, acceptedBytes: Long('95') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:44.859Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('82') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:44.933Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 2, acceptedBytes: Long('98') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:45.008Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('66') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:46.734Z') } ] usage_outbox_total=5 test>
collector client: 2026-11-10T21:17:43.302-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Sending usage fact to http://127.0.0.1:8081/internal/usage-facts headerNames=[Content-Type, X-LhamaLog-Service-Token] authorizationPresent=false contentType=application/json tokenLength=64 tokenSha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:43.317-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Core usage-fact response 201 || 2026-11-10T21:17:45.417-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Sending usage fact to http://127.0.0.1:8081/internal/usage-facts headerNames=[Content-Type, X-LhamaLog-Service-Token] authorizationPresent=false contentType=application/json tokenLength=64 tokenSha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:45.430-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Core usage-fact response 201 || 2026-11-10T21:17:45.524-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Sending usage fact to http://127.0.0.1:8081/internal/usage-facts headerNames=[Content-Type, X-LhamaLog-Service-Token] authorizationPresent=false contentType=application/json tokenLength=64 tokenSha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:45.537-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Core usage-fact response 201 || 2026-11-10T21:17:45.628-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Sending usage fact to http://127.0.0.1:8081/internal/usage-facts headerNames=[Content-Type, X-LhamaLog-Service-Token] authorizationPresent=false contentType=application/json tokenLength=64 tokenSha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:45.640-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Core usage-fact response 201 || 2026-11-10T21:17:47.737-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Sending usage fact to http://127.0.0.1:8081/internal/usage-facts headerNames=[Content-Type, X-LhamaLog-Service-Token] authorizationPresent=false contentType=application/json tokenLength=64 tokenSha256=67e3b3267e4dc34bc4a39e35779962cd0bc228ca4453edcb124c476380988195 || 2026-11-10T21:17:47.748-03:00  INFO 39536 --- [lhamalog-collector] [ usage-outbox-1] [                                                 ] b.c.l.c.usage.core.CoreUsageFactsClient  : Core usage-fact response 201
core 401 reasons (run-1, antes do restart): nenhuma linha Rejected
GET usage-facts A: HTTP 200 body={"items":[{"usageFactId":"adf2d3cd84469ce78b9b534133794f86759f5a6fc485b636c38cd0aacb2e290c","batchId":"4416f21d613645083a4f29a68bb2005fdd8e28a1e1e0443e6edaef4373955012","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["e1ef90bc-2f94-3e1c-9b48-6c7e6afa069a"],"acceptedRecordCount":1,"acceptedBytes":74,"acceptedAt":"2026-11-11T00:17:42.569Z","schemaVersion":1},{"usageFactId":"089cf38e2214e0101eddeffc59d7d0d282bbf699e6b81d96b8fc50874db8d112","batchId":"17b81c8741891edf32d74094b997a09e3510cb072ab7045f8cd4eece6eb52ae9","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["2eff9c23-5d88-39e2-b07a-b9b9197a63a3"],"acceptedRecordCount":1,"acceptedBytes":82,"acceptedAt":"2026-11-11T00:17:44.933Z","schemaVersion":1},{"usageFactId":"bb9e13065c61ea39cfb4554cc1021d5e04fadb871423ea96a811e60d4712680b","batchId":"af657841859e186ec02bea569ae0a4d186bebd132ee947dddd28770a042ba837","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["e1ef90bc-2f94-3e1c-9b48-6c7e6afa069a","ba36323a-0e8c-3781-82bb-488db707960d"],"acceptedRecordCount":2,"acceptedBytes":98,"acceptedAt":"2026-11-11T00:17:45.008Z","schemaVersion":1},{"usageFactId":"cdc34087f2a7575c40d02c4bf1882df0437b9d11a4df7f2741ff732976763a96","batchId":"15e5439d43fa2b1498bd16c6a7f7c5abb0b6b65f71220c6a8df05e9666c1f052","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["0db97aaa-69fe-36d7-9191-a2e7e5b326e4"],"acceptedRecordCount":1,"acceptedBytes":66,"acceptedAt":"2026-11-11T00:17:46.733Z","schemaVersion":1}],"nextCursor":null}
GET usage-facts B: HTTP 200 body={"items":[{"usageFactId":"ba3229170fe76790b16ba0462a3104131903931bc5e4f23da6e5582c636387d5","batchId":"c3f763e8baea95f9653ee1a9f81cc37f4ea3b5125f8fb859a53b8f8570341514","tenantId":"tenant-e2e-b","productId":"product-e2e-b","domain":"TRACES","acceptedRecordIds":["dbb49e6f-27ba-3958-a1f4-adace81f5612"],"acceptedRecordCount":1,"acceptedBytes":95,"acceptedAt":"2026-11-11T00:17:44.859Z","schemaVersion":1}],"nextCursor":null}
GET current-cycle A: HTTP 200 body={"active":true,"from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","logs":{"acceptedRecordCount":5,"acceptedBytes":320,"acceptedGigabytes":3.2E-7},"traces":{"acceptedRecordCount":0,"acceptedBytes":0,"acceptedGigabytes":0.0}}
GET current-cycle B: HTTP 200 body={"active":true,"from":"2026-11-08T00:00:00Z","to":"2026-11-15T00:00:00Z","logs":{"acceptedRecordCount":0,"acceptedBytes":0,"acceptedGigabytes":0.0},"traces":{"acceptedRecordCount":1,"acceptedBytes":95,"acceptedGigabytes":9.5E-8}}
simulacao: parar Core e enviar lote C
OTLP HTTP JSON lote C com Core fora: 200
comando: mvnw.cmd spring-boot:run (Core restart; log llt186-core-run-2.log)
Core voltou (health OK); aguardando worker
GET usage-facts A apos retry: HTTP 200 body={"items":[{"usageFactId":"adf2d3cd84469ce78b9b534133794f86759f5a6fc485b636c38cd0aacb2e290c","batchId":"4416f21d613645083a4f29a68bb2005fdd8e28a1e1e0443e6edaef4373955012","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["e1ef90bc-2f94-3e1c-9b48-6c7e6afa069a"],"acceptedRecordCount":1,"acceptedBytes":74,"acceptedAt":"2026-11-11T00:17:42.569Z","schemaVersion":1},{"usageFactId":"089cf38e2214e0101eddeffc59d7d0d282bbf699e6b81d96b8fc50874db8d112","batchId":"17b81c8741891edf32d74094b997a09e3510cb072ab7045f8cd4eece6eb52ae9","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["2eff9c23-5d88-39e2-b07a-b9b9197a63a3"],"acceptedRecordCount":1,"acceptedBytes":82,"acceptedAt":"2026-11-11T00:17:44.933Z","schemaVersion":1},{"usageFactId":"bb9e13065c61ea39cfb4554cc1021d5e04fadb871423ea96a811e60d4712680b","batchId":"af657841859e186ec02bea569ae0a4d186bebd132ee947dddd28770a042ba837","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["e1ef90bc-2f94-3e1c-9b48-6c7e6afa069a","ba36323a-0e8c-3781-82bb-488db707960d"],"acceptedRecordCount":2,"acceptedBytes":98,"acceptedAt":"2026-11-11T00:17:45.008Z","schemaVersion":1},{"usageFactId":"cdc34087f2a7575c40d02c4bf1882df0437b9d11a4df7f2741ff732976763a96","batchId":"15e5439d43fa2b1498bd16c6a7f7c5abb0b6b65f71220c6a8df05e9666c1f052","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["0db97aaa-69fe-36d7-9191-a2e7e5b326e4"],"acceptedRecordCount":1,"acceptedBytes":66,"acceptedAt":"2026-11-11T00:17:46.733Z","schemaVersion":1},{"usageFactId":"c34b522460fabfbcf8da95f0b129fb189b8ccc15a2533e0ea49cb78b63279b85","batchId":"b9e669d7b158b6880c6628b131be16ec26724e2be6057368428e624e6c04c04a","tenantId":"tenant-e2e-a","productId":"product-e2e-a","domain":"LOGS","acceptedRecordIds":["15a2b4ad-8ec5-3266-8476-826b5c961da2"],"acceptedRecordCount":1,"acceptedBytes":79,"acceptedAt":"2026-11-11T00:18:11.888Z","schemaVersion":1}],"nextCursor":null}
GET current-cycle A apos retry: HTTP 200 body={"active":true,"from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","logs":{"acceptedRecordCount":6,"acceptedBytes":399,"acceptedGigabytes":3.99E-7},"traces":{"acceptedRecordCount":0,"acceptedBytes":0,"acceptedGigabytes":0.0}}
GET current-cycle B apos retry: HTTP 200 body={"active":true,"from":"2026-11-08T00:00:00Z","to":"2026-11-15T00:00:00Z","logs":{"acceptedRecordCount":0,"acceptedBytes":0,"acceptedGigabytes":0.0},"traces":{"acceptedRecordCount":1,"acceptedBytes":95,"acceptedGigabytes":9.5E-8}}
outbox states apos retry: test> [ { _id: 'SYNCED', n: 6 } ] [ { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('74') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:42.569Z') }, { fact: { domain: 'TRACES', acceptedRecordCount: 1, acceptedBytes: Long('95') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:44.859Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('82') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:44.933Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 2, acceptedBytes: Long('98') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:45.008Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('66') }, state: 'SYNCED', attemptCount: 1, nextAttemptAt: ISODate('2026-11-11T00:17:46.734Z') }, { fact: { domain: 'LOGS', acceptedRecordCount: 1, acceptedBytes: Long('79') }, state: 'SYNCED', attemptCount: 4, nextAttemptAt: ISODate('2026-11-11T00:18:21.856Z'), lastErrorCode: 'NETWORK' } ] usage_outbox_total=6 test>
core 401 reasons (run-2): nenhuma linha Rejected
ensaio T046 concluido sem imprimir tokens ou senhas
teardown: removido container Mongo do Core E2E
teardown: docker compose down (sem -v)
