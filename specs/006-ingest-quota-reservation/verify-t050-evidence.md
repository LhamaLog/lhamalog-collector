# Evidência T050 — verify local (2026-11-11)

## Collector

Comando: `.\mvnw.cmd -q verify`

Surefire: **217** tests, **0** failures, **0** errors, **0** skipped. Failsafe: nenhum relatório.

## Core (`lhamalog-core-llt186`)

Comando: `$env:TEST_MONGODB_URI='mongodb://127.0.0.1:27022/lhamalog-core-test'; .\mvnw.cmd -q verify`

Mongo descartável: `docker run -d --name llt186-impl-mongo -p 27022:27017 mongo:8.0.34` (removido ao final).

Surefire: **47** tests, **0** failures, **0** errors, **0** skipped. Failsafe: nenhum relatório.

## Observação

T049 (cenários E2E do quickstart nos três transportes OTLP) permanece pendente para o orquestrador.
