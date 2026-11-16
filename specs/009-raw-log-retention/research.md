# Research: 009-raw-log-retention

## R1 — Destino de armazenamento

- **Decision**: OpenObserve (ADR_168), não Elasticsearch/Mongo para logs brutos.
- **Rationale**: Card LLT-185 citava ES/Mongo; ADR_168 e Michel (16/11/2026) substituíram.
- **Alternatives**: Elasticsearch ILM — rejeitado; Mongo TTL — rejeitado para telemetria bruta.

## R2 — Um stream por tenant

- **Decision**: `logs_<tenant>` / `traces_<tenant>` (prefixo configurável).
- **Rationale**: Retenção OO é por stream (`data_retention`); isolamento por tenant habilita planos diferentes.
- **Alternatives**: Stream único + filtro — não aplica retenção por plano; stream por produto — fragmenta demais e o stream é por tenant (decisão Michel).

## R3 — Regra de nome do stream

- **Decision**: `{prefix}_{sanitized}` onde sanitized = tenantId lowercased, fora de `[a-z0-9]` → `_`, truncado a 40; se sanitização alterou ou truncou, acrescentar `_` + 8 hex de SHA-256(tenantId UTF-8).
- **Rationale**: OpenObserve normaliza nomes para `[a-zA-Z0-9_:]` (fora → `_`); regra mais restrita (só minúsculas) evita surpresas. Hash evita colisão `Org-A` vs `org_a`.
- **Alternatives**: Só hash — ilegível; UUID por tenant em mapa — estado extra.

## R4 — Contrato `retentionDays`

- **Decision**: Campo opcional inteiro na JSON de reserva que autoriza; ausente/null/≤0 = desconhecido. Nome inglês no fio; Core mapeia de `retencaoDias`.
- **Rationale**: Tolerant reader; Collectors/Cores mistos na transição.
- **Agregação**: máximo entre as assinaturas consideráveis do tenant (ACTIVE e DELINQUENT dentro da carência; DELINQUENT vencida fica fora, ajuste da review de 16/11 17:36); sem `planTier` conta como FREE (7). Decidido pelo Michel em 16/11/2026 16:36.

## R5 — Quando aplicar settings

- **Decision**: Após ingest `_json` bem-sucedida (stream já existe). PUT `.../settings?type=logs|traces` body `{"data_retention": N}`.
- **Rationale**: Docs OO: update settings em stream existente; 404 se não existir. Ingest cria o stream.
- **Nota v1.1.2**: confirmar empiricamente no teste de integração; se body mínimo for rejeitado, ajustar para o schema UpdateStreamSettings da versão.

## R6 — Cache e falhas

- **Decision**: Caffeine (já no pom) chave `(type, stream)` → N; TTL 1 h; max size limitado (ex. 10_000). Falha → não cachear sucesso; negative-cache backoff 60 s; WARN + counter Micrometer.
- **Rationale**: Evita PUT a cada lote; TTL re-aplica mudanças manuais; backoff evita martelar OO.
- **Caminho crítico**: aplicar **async** após aceite (Executor + correlation id propagado). Nunca falhar a resposta OTLP por settings.

## R7 — Quota desligada

- **Decision**: Sem reserva → sem `retentionDays` → só stream do tenant; retenção = global `ZO_COMPACT_DATA_RETENTION_DAYS`.
- **Rationale**: Spec explícita; não inventar default de plano.

## R8 — Mensagem Redis / Finder

- **Decision**: Campo `openobserve.stream` passa a carregar o stream do tenant; sem novo campo; sem bump `schemaVersion`.
- **Rationale**: Finder já lê o valor; só muda a string.

## R9 — Migração legado

- **Decision**: Só runbook: legado permanece até retenção global; UNION/duas consultas; export manual opcional; delete manual quando seguro.
- **Rationale**: Escopo LLT-185 não inclui job de migração.

## R10 — Testes OpenObserve

- **Decision**: Testcontainers `public.ecr.aws/zinclabs/openobserve:v1.1.2` (já em `TestcontainersConfiguration`); assert via `GET .../schema?type=logs`. Fallback: HttpServer stub se OO container inviável no CI.
- **Rationale**: Mesma imagem do compose; credenciais aleatórias por run.
