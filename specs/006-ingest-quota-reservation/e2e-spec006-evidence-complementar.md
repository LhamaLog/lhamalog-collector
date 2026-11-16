# Evidências E2E spec006 (LLT-186) — execução complementar (lote acima do saldo, replays diretos de confirm/refund)

Data: 2026-11-11 08:34:34 (America/Sao_Paulo)

Nenhum token, senha ou segredo foi registrado. Script: %TEMP%\llt186-spec006-e2e.ps1; envio OTLP: %TEMP%\llt186-otlp-send.py.

## Preparação
- token de serviço gerado por execução, só no ambiente do processo: length=64 sha256=fbb89c7bf34bccad89b71e68af00c48dd8a8012cba45487f40ce72e7b2ca9917
- infra: docker compose -p lhamalog-llt186-e2e (Mongo/Redis/OpenObserve/otel do Collector) + Mongo descartável do Core na porta 27019
- seed Core: tenant-q; p-cal/p-d/p-e/p-t com 1 GB decimal; p-f com firstPaymentDate 2026-12-01 (futuro); p-u unlimitedUsage=true; p-c sem assinatura; p-a e p-b semeados após calibração
- Core (8081, TTL de reserva PT25S e reaper PT5S só no E2E, notificações Redis ligadas) e Collector (quota.enabled=true, refund worker 15s, injeção de falha E2E ligada) em execução, janelas ocultas

## Calibração e débito (p-cal, 1 GB)
- lote 1 registro HTTP JSON: status=200 retry_after=None msg=
- saldo p-cal após débito: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"} => S (bytes canônicos de 1 registro) = 78
- reservas p-cal: [ { requestedBytes: Long('78'), state: 'CONFIRMED', reservationId: 'quota:daff8f05f1ba' } ]
- reenvio idêntico (mesmo reservationId determinístico): status=200 retry_after=None msg=
- saldo p-cal após reenvio: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"} (consumo deve permanecer 78)
- confirm direto repetido no Core (replay): HTTP 204  / HTTP 204 
- saldo p-cal após replays: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":78,"remainingBytes":999999922,"cycleStart":"2026-11-01T00:00:00Z"}
- fatos de uso spec005 p-cal no outbox do Collector: 1
- seed p-a: 0.00000156 GB (= 20 x S bytes); p-b: 0.000000088 GB (= S + 10 bytes, menor que um lote de 2 registros e maior que um de 1)

## Lote acima do saldo recusado integralmente (p-b = S + 10 bytes)
- lote de 2 registros: status=429 retry_after=120 msg=ingestion quota exhausted, retry later
- saldo p-b após recusa: HTTP 200 {"allowanceBytes":88,"reservedBytes":0,"consumedBytes":0,"remainingBytes":88,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso p-b: 0
- lote de 1 registro: status=200 retry_after=None msg=
- saldo p-b final: HTTP 200 {"allowanceBytes":88,"reservedBytes":0,"consumedBytes":76,"remainingBytes":12,"cycleStart":"2026-11-01T00:00:00Z"}; fatos de uso p-b: 1; reservas: [ { requestedBytes: Long('76'), state: 'CONFIRMED', reservationId: 'quota:9cc3c525f8cf' } ]

## Refund com Core disponível e replay direto do refund (p-e)
- falha de escrita pós-débito em p-e: status=503 retry_after=120 msg=destination unavailable, retry later
- outbox refund: [ { state: 'DONE', reason: 'SINK_UNAVAILABLE', attemptCount: 0 } ]
- reservas p-e: [ { requestedBytes: Long('95'), state: 'REFUNDED', reservationId: 'quota:262f77c2b460' } ]; saldo p-e: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}
- refund direto repetido (replay) x2: HTTP 204  / HTTP 204 ; saldo p-e: HTTP 200 {"allowanceBytes":1000000000,"reservedBytes":0,"consumedBytes":0,"remainingBytes":1000000000,"cycleStart":"2026-11-01T00:00:00Z"}
- release de reserva REFUNDED: HTTP 409 ; confirm de reserva REFUNDED: HTTP 409 
- id desconhecido: confirm HTTP 404  / refund HTTP 404 

## Logs de segurança
- ERRO: Não é possível localizar o caminho 'C:\Users\miche\AppData\Local\Temp\llt186-q2-core-2.log' porque ele não existe.
- teardown: Mongo do Core E2E removido
- teardown: docker compose down (sem -v)

> Nota: a varredura do token nos logs desta execução complementar falhou só porque o Core não foi reiniciado (o log run-2 não existe); o processo encerrou sem gravar tokens. A varredura da execução principal deu 0 ocorrências.
