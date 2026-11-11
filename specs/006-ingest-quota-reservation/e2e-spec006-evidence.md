# Evidências E2E spec006 (LLT-186) — reserva, débito, recusa, refund e SALDO_BAIXO

Data: 2026-11-11 08:25:38 (America/Sao_Paulo)

Nenhum token, senha ou segredo foi registrado. Script: %TEMP%\llt186-spec006-e2e.ps1; envio OTLP: %TEMP%\llt186-otlp-send.py.

## Preparação
- token de serviço gerado por execução, só no ambiente do processo: length=64 sha256=bcda8d9bcccee7cc2ddb7051ed6a348df77c374c12a8347b9feb2a150a597d4c
- infra: docker compose -p lhamalog-llt186-e2e (Mongo/Redis/OpenObserve/otel do Collector) + Mongo descartável do Core na porta 27019
- seed Core: tenant-q; p-cal/p-d/p-e/p-t com 1 GB decimal; p-f com firstPaymentDate 2026-12-01 (futuro); p-u unlimitedUsage=true; p-c sem assinatura; p-a e p-b semeados após calibração
- Core (8081, TTL de reserva PT25S e reaper PT5S só no E2E, notificações Redis ligadas) e Collector (quota.enabled=true, refund worker 15s, injeção de falha E2E ligada) em execução, janelas ocultas

## Calibração e débito (p-cal, 1 GB)
- lote 1 registro HTTP JSON: status=200 retry_after=None msg=
- saldo p-cal após débito: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"} => S (bytes canônicos de 1 registro) = 78
- reservas p-cal: lhamalog_core> [ { requestedBytes: Long('78'), state: 'CONFIRMED', reservationId: 'quota:daff8f05f1ba' } ] lhamalog_core>
- reenvio idêntico (mesmo reservationId determinístico): status=200 retry_after=None msg=
- saldo p-cal após reenvio: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"} (consumo deve permanecer 78)
- confirm direto repetido no Core (replay): HTTP 404  / HTTP 404 
- saldo p-cal após replays: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"}
- fatos de uso spec005 p-cal no outbox do Collector: lhamalog_collector> 1 lhamalog_collector>
- seed p-a: 0.00000156 GB (= 20 x S bytes); p-b: 0.000000117 GB (= 1,5 x S bytes)

## Sem plano / antes do primeiro pagamento (503)
- p-c sem assinatura HTTP JSON: status=503 retry_after=120 msg=ingestion quota unavailable, retry later
- p-c HTTP protobuf: status=503 retry_after=120 rpc_code=14
- p-c gRPC: grpc=UNAVAILABLE retry_after=120
- p-f firstPaymentDate futura HTTP JSON: status=503 retry_after=120 msg=ingestion quota unavailable, retry later
- fatos de uso p-c/p-f: lhamalog_collector> 0 lhamalog_collector> / lhamalog_collector> 0 lhamalog_collector>; reservas p-c: lhamalog_core> [] lhamalog_core>

## Uso ilimitado (p-u)
- p-u HTTP JSON: status=200 retry_after=None msg=; balance: HTTP 404 ; reservas: lhamalog_core> [ { requestedBytes: Long('76'), state: 'CONFIRMED', reservationId: 'quota:0386c80bb6a1' } ] lhamalog_core>

## Lote acima do saldo recusado integralmente (p-b = 1,5 x S)
- lote de 2 registros: status=200 retry_after=None msg=
- saldo p-b após recusa: HTTP 200 {"allowanceBytes":117,"reservedBytes":0,"consumedBytes":102,"remainingBytes":15,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso p-b: lhamalog_collector> 1 lhamalog_collector>
- lote de 1 registro: status=429 retry_after=120 msg=ingestion quota exhausted, retry later
- saldo p-b final: HTTP 200 {"allowanceBytes":117,"reservedBytes":0,"consumedBytes":102,"remainingBytes":15,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso p-b: lhamalog_collector> 1 lhamalog_collector>; reservas: lhamalog_core> [ { requestedBytes: Long('102'), state: 'CONFIRMED', reservationId: 'quota:ffcbafa8e12d' } ] lhamalog_core>

## Release quando a sanitização rejeita após a reserva (p-d)
- lote com marcador __e2e_sanitizer_reject__: status=400 retry_after=None msg=batch rejected during sanitization
- reservas p-d: lhamalog_core> [ { requestedBytes: Long('101'), state: 'RELEASED', reservationId: 'quota:83673a663b3b' } ] lhamalog_core>; saldo: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso: lhamalog_collector> 0 lhamalog_collector>

## Expiração TTL de reserva nunca confirmada (p-t, TTL E2E 25 s)
- reserve direto no Core (simula Collector que caiu antes do confirm): HTTP 201 {"outcome":"AUTHORIZED","reservationId":"e2e-ttl-0001","expiresAt":"2026-11-11T11:22:47.780661200Z"}
- saldo p-t logo após: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":1234,"consumedBytes":0,"remainingBytes":999998766,"cycleStart":"2026-11-01T00:00:00Z"}; reservas: lhamalog_core> [ { requestedBytes: Long('1234'), state: 'RESERVED', reservationId: 'e2e-ttl-0001' } ] lhamalog_core>
- após 40 s: saldo p-t: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}; reservas: lhamalog_core> [ { requestedBytes: Long('1234'), state: 'EXPIRED', reservationId: 'e2e-ttl-0001' } ] lhamalog_core>
- confirm tardio: HTTP 409 
- saldo p-t após confirm tardio: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}

## Débitos sucessivos, SALDO_BAIXO 20%/5%, refund sem reemissão e 429 (p-a = 20 x S)
- lote a-0001: status=200 retry_after=None msg= | consumed=76 reserved=0 remaining=1484 (95,1%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0002: status=200 retry_after=None msg= | consumed=152 reserved=0 remaining=1408 (90,3%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0003: status=200 retry_after=None msg= | consumed=228 reserved=0 remaining=1332 (85,4%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0004: status=200 retry_after=None msg= | consumed=304 reserved=0 remaining=1256 (80,5%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0005: status=200 retry_after=None msg= | consumed=380 reserved=0 remaining=1180 (75,6%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0006: status=200 retry_after=None msg= | consumed=456 reserved=0 remaining=1104 (70,8%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0007: status=200 retry_after=None msg= | consumed=532 reserved=0 remaining=1028 (65,9%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0008: status=200 retry_after=None msg= | consumed=608 reserved=0 remaining=952 (61%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0009: status=200 retry_after=None msg= | consumed=684 reserved=0 remaining=876 (56,2%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0010: status=200 retry_after=None msg= | consumed=760 reserved=0 remaining=800 (51,3%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0011: status=200 retry_after=None msg= | consumed=836 reserved=0 remaining=724 (46,4%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0012: status=200 retry_after=None msg= | consumed=912 reserved=0 remaining=648 (41,5%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0013: status=200 retry_after=None msg= | consumed=988 reserved=0 remaining=572 (36,7%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0014: status=200 retry_after=None msg= | consumed=1064 reserved=0 remaining=496 (31,8%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0015: status=200 retry_after=None msg= | consumed=1140 reserved=0 remaining=420 (26,9%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0016: status=200 retry_after=None msg= | consumed=1216 reserved=0 remaining=344 (22,1%) | itens SALDO_BAIXO=lhamalog_core> 0 lhamalog_core>
- lote a-0017: status=200 retry_after=None msg= | consumed=1292 reserved=0 remaining=268 (17,2%) | itens SALDO_BAIXO=lhamalog_core> 1 lhamalog_core>
- falha de escrita pós-débito em p-a (marcador __e2e_write_fail__): status=503 retry_after=120 msg=destination unavailable, retry later
- após worker de refund: consumed=1292 remaining=268; outbox refund: lhamalog_collector> [ { state: 'DONE', reason: 'SINK_UNAVAILABLE', attemptCount: 0 } ] lhamalog_collector>; itens SALDO_BAIXO=lhamalog_core> 1 lhamalog_core>
- lote a-0018: status=200 retry_after=None msg= | consumed=1368 reserved=0 remaining=192 (12,3%) | itens SALDO_BAIXO=lhamalog_core> 1 lhamalog_core>
- lote a-0019: status=200 retry_after=None msg= | consumed=1444 reserved=0 remaining=116 (7,4%) | itens SALDO_BAIXO=lhamalog_core> 1 lhamalog_core>
- lote a-0020: status=200 retry_after=None msg= | consumed=1520 reserved=0 remaining=40 (2,6%) | itens SALDO_BAIXO=lhamalog_core> 2 lhamalog_core>
- lote a-0021: status=429 retry_after=120 msg=ingestion quota exhausted, retry later | consumed=1520 reserved=0 remaining=40 (2,6%) | itens SALDO_BAIXO=lhamalog_core> 2 lhamalog_core>
- 429 também por HTTP protobuf: status=429 retry_after=120 rpc_code=8 e gRPC: grpc=RESOURCE_EXHAUSTED retry_after=120
- reservas p-a por estado: lhamalog_core> [ { _id: 'REFUNDED', n: 1 }, { _id: 'CONFIRMED', n: 20 } ] lhamalog_core>
- outbox SALDO_BAIXO (Core): lhamalog_core> [ { _id: 'tenant-q+p-b+2026-11-01T00:00:00Z+20', threshold: 20, remainingBytes: Long('15'), type: 'SALDO_BAIXO', severity: 'MEDIUM', mandatoryRoles: [ 'DONO', 'ADMINISTRADOR' ], mandatoryChannels: [ 'WEB', 'EMAIL' ], state: 'PUBLISHED' }, { _id: 'tenant-q+p-a+2026-11-01T00:00:00Z+20', threshold: 20, remainingBytes: Long('268'), type: 'SALDO_BAIXO', severity: 'MEDIUM', mandatoryRoles: [ 'DONO', 'ADMINISTRADOR' ], mandatoryChannels: [ 'WEB', 'EMAIL' ], state: 'PUBLISHED' }, { _id: 'tenant-q+p-a+2026-11-01T00:00:00Z+5', threshold: 5, remainingBytes: Long('40'), type: 'SALDO_BAIXO', severity: 'MEDIUM', mandatoryRoles: [ 'DONO', 'ADMINISTRADOR' ], mandatoryChannels: [ 'WEB', 'EMAIL' ], state: 'PUBLISHED' } ] lhamalog_core>
- stream Redis lhamalog:notifications:events (XRANGE): 1794396136398-0 tenantId tenant-q severity MEDIUM mandatoryRoles DONO,ADMINISTRADOR type SALDO_BAIXO mandatoryChannels WEB,EMAIL threshold 20 cycleStart 2026-11-01T00:00:00Z productId p-b remainingBytes 15 idempotencyKey tenant-q+p-b+2026-11-01T00:00:00Z+20 1794396200217-0 tenantId tenant-q severity MEDIUM mandatoryRoles DONO,ADMINISTRADOR type SALDO_BAIXO mandatoryChannels WEB,EMAIL threshold 20 cycleStart 2026-11-01T00:00:00Z productId p-a remainingBytes 268 idempotencyKey tenant-q+p-a+2026-11-01T00:00:00Z+20 1794396229539-0 tenantId tenant-q severity MEDIUM mandatoryRoles DONO,ADMINISTRADOR type SALDO_BAIXO mandatoryChannels WEB,EMAIL threshold 5 cycleStart 2026-11-01T00:00:00Z productId p-a remainingBytes 40 idempotencyKey tenant-q+p-a+2026-11-01T00:00:00Z+5

## Refund com Core fora e retry pelo outbox (p-e) + Core fora na ingestão
- falha de escrita pós-débito em p-e: status=503 retry_after=120 msg=destination unavailable, retry later; Core parado imediatamente em seguida
- ingestão com Core fora (p-cal) HTTP JSON: status=503 retry_after=120 msg=ingestion quota unavailable, retry later
- ingestão com Core fora gRPC: grpc=UNAVAILABLE retry_after=120
- outbox refund com Core fora: lhamalog_collector> [ { state: 'DONE', reason: 'SINK_UNAVAILABLE', attemptCount: 0, nextAttemptAt: ISODate('2026-11-11T11:23:21.087Z') }, { state: 'PENDING', reason: 'SINK_UNAVAILABLE', attemptCount: 2, nextAttemptAt: ISODate('2026-11-11T11:24:18.330Z') } ] lhamalog_collector>
- Core reiniciado
- outbox refund após Core voltar: lhamalog_collector> [ { state: 'DONE', reason: 'SINK_UNAVAILABLE', attemptCount: 0 }, { state: 'DONE', reason: 'SINK_UNAVAILABLE', attemptCount: 4 } ] lhamalog_collector>
- reservas p-e: lhamalog_core> [ { requestedBytes: Long('95'), state: 'REFUNDED', reservationId: 'quota:29a4b8a2cb18' } ] lhamalog_core>; saldo p-e: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso p-e: lhamalog_collector> 0 lhamalog_collector>
- refund direto repetido (replay): HTTP 404 ; saldo p-e: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}
- refund de reserva EXPIRED (não reembolsável): HTTP 409 

## Logs de segurança
- ocorrências do token de serviço em claro nos logs: 0
- ensaio concluído sem gravar tokens/senhas
- teardown: Mongo do Core E2E removido
- teardown: docker compose down (sem -v)
