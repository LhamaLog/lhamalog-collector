# Feature Specification: Expor métricas e logs operacionais do Collector

**Feature Branch**: `feature/LLT-187-collector-metrics` (spec dir `008-collector-metrics`)

**Created**: 2026-11-16

**Status**: Clarified — 16/11/202 sofrido decisões do Michel (Q3/Q4 + Loki)

**Input**: User description: "Expor métricas do Collector (Prometheus scrape + Grafana + Loki) com exatamente cinco métricas novas aprovadas, sem tenantId/productId em tags, porta de management separada e stack local de observabilidade."

**Jira**: LLT-187 — Expor métricas do Collector.

## Fontes

- Decisões do Michel em 16/11/2026 (Q3 dashboards, Q4 métricas aprovadas às 13:37, Loki às 13:36).
- Constituição 2.1.0 (princípio VI de logs; inglês no código / pt-BR na documentação).
- Métricas já existentes no Collector (recusas, quota, usage outbox) e export OTLP atual.
- ADR_168: OpenObserve permanece o sink de telemetria **ingerida** (logs/traces de clientes); esta feature cobre métricas/logs **operacionais do próprio Collector**.

## Separação de escopo

| Inclui | Não inclui |
|--------|------------|
| Scraping Prometheus das métricas do Collector; Grafana local; Loki + Alloy para logs do processo Collector | Alterar Core; TelemetryIngestedPublisher / correlationId de stream (T050 / LLT-183) |
| Cinco métricas novas aprovadas (Q4) + documentação de todas as métricas existentes | Qualquer métrica nova além das cinco aprovadas |
| Porta de management separada com health/info/prometheus | Expor `/actuator/env` ou endpoints sensíveis |
| Labels Loki de baixa cardinalidade (`service`, `level`, opcional `env`) | Labels com tenantId/productId/token/payload |

## Clarifications

### Sessão 16/11/2026 — decisões do Michel

Registro: **Decidido pelo Michel em 16/11/2026**.

- **Q3 (dashboards)**: dashboards com **Prometheus + Grafana** na infra local. OpenObserve continua o sink de telemetria ingerida (ADR_168); esta feature trata das métricas/logs **próprios** do Collector.
- **Q4 (métricas novas)**: adiada às 13:35 e **aprovada às 13:37**. Implementar **exatamente** estas cinco, **nunca** `tenantId`/`productId` como tag:
  1. `lhamalog.ingestion.accepted.records` (counter; tags `signal`=logs\|traces, `protocol`=http\|grpc) — só lotes aceitos após sink/publish ok; bytes = mesma medida canônica de uso/volume.
  2. `lhamalog.ingestion.accepted.bytes` (counter; mesmas tags).
  3. `lhamalog.ingestion.latency` (timer/histogram; tags `signal`, `protocol`, `outcome`=accepted\|refused\|failed) — tempo total do handling OTLP nos receivers HTTP e gRPC, para todo outcome; buckets/SLO para p50/p95/p99; cardinalidade limitada.
  4. `lhamalog.queue.size` (gauge; tag `signal`) — XLEN de `lhamalog:stream:logs|traces`.
  5. `lhamalog.dlq.size` (gauge; tag `signal`) — XLEN das DLQs correspondentes.
  - Refresh agendado (default 15s, propriedade `lhamalog.metrics.*`); em falha Redis manter último valor (ou NaN antes do primeiro sucesso) e WARN rate-limited `event=metrics.queue_size.refresh_failed | ... | signal=...`. Ativo só com messaging/Redis habilitado.
- **Loki (13:36)**: incluir Loki com Prometheus e Grafana. Logs do Collector → Loki → Grafana (datasource provisionado). Labels só baixa cardinalidade (`service`, `level`, talvez `env`); **nunca** tenantId. Linha de log permanece no padrão princípio VI. Dashboard com painel de logs filtrável por `cid` (variável textbox) + exemplo Explore em docs (`{service="lhamalog-collector"} |= "cid=<id>"`).
- **Redis / .env.example**: preencher placeholders óbvios (`change-me` / `Change-me-123!`); adicionar variáveis do stack de métricas (portas, Grafana admin). Valores reais só no `.env` não versionado.
- **Fora de escopo explícito**: Core; TelemetryIngestedPublisher / stream correlationId.

### Perguntas remanescentes (não bloqueantes — recomendações do dev)

- **R1 — Binding do management address em local Docker Desktop**: default vazio (todas as interfaces) para o container Prometheus alcançar via `host.docker.internal`; em prod documentar bind `127.0.0.1` ou rede interna. **Recomendação**: adotar.
- **R2 — Buckets do histogram de latência**: conjunto fixo SLO (ex.: 5ms–30s). **Recomendação**: `{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30}` segundos.
- **R3 — Anonymous viewer no Grafana**: permitido **somente** no compose de observabilidade local; documentado. **Recomendação**: habilitar anonymous Viewer no local.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scrapear métricas operacionais com segurança (Priority: P1)

Operador de plataforma consulta um endpoint de scrape Prometheus na porta de management e vê métricas JVM + `lhamalog_*` sem autenticação nesse endpoint; demais endpoints sensíveis do Actuator não ficam expostos; a porta principal da aplicação continua protegida.

**Why this priority**: Sem scrape seguro não há dashboard nem as cinco métricas novas.

**Independent Test**: Subir a app, incrementar um counter, GET `/actuator/prometheus` na porta de management → 200 texto com `lhamalog_`; GET `/actuator/env` negado/não exposto; rota não-actuator na porta 8080 ainda exige auth.

**Acceptance Scenarios**:

1. **Given** o Collector no ar, **When** o scraper chama o endpoint Prometheus na porta de management, **Then** recebe texto Prometheus 200 com métricas `lhamalog_` e JVM.
2. **Given** o mesmo processo, **When** tenta endpoints Actuator não permitidos (ex. env/metrics detalhado), **Then** não obtém dados sensíveis (negado ou não exposto).
3. **Given** tags proibidas tentadas em um meter, **When** o meter é registrado, **Then** tags `tenantId`/`productId`/`token` (e variantes) são removidas.

---

### User Story 2 - Cinco métricas de ingestão e filas (Priority: P1)

Operador enxerga throughput aceito (records/s e bytes/s), latência p50/p95/p99 por sinal/protocolo/outcome, e profundidade das filas Redis/DLQ por sinal — sem identidade de tenant nas tags.

**Why this priority**: Escopo central aprovado (Q4).

**Independent Test**: Unitários com SimpleMeterRegistry por meter; teste de scrape mostrando os nomes Prometheus; simular aceite/recusa/falha e refresh de fila.

**Acceptance Scenarios**:

1. **Given** um lote aceito (sink/publish ok), **When** a decisão é Accepted/PartiallyAccepted com records, **Then** `accepted.records` e `accepted.bytes` incrementam com tags `signal` e `protocol` e bytes = medida canônica de uso.
2. **Given** qualquer requisição OTLP HTTP/gRPC, **When** o handling termina, **Then** `ingestion.latency` registra duração com `outcome` accepted|refused|failed.
3. **Given** Redis/messaging ativo, **When** o refresher roda, **Then** `queue.size` e `dlq.size` refletem XLEN; em falha Redis mantém último valor e emite WARN rate-limited.

---

### User Story 3 - Stack local Prometheus + Grafana + Loki (Priority: P2)

Desenvolvedor sobe o compose de observabilidade separado, abre Grafana com dashboards provisionados (métricas + logs filtráveis por cid) e consulta Loki sem misturar segredos do compose principal.

**Why this priority**: Validação visual e debug por correlation id; depende das US1/US2.

**Independent Test**: `docker compose ... config -q`; Makefile `metrics-up/down/ps/logs`; painéis documentados; Explore LogQL por cid.

**Acceptance Scenarios**:

1. **Given** compose.observability.yaml, **When** valida a config, **Then** passa sem exigir secrets do compose principal.
2. **Given** Collector escrevendo log file + Alloy tail, **When** há linhas princípio VI, **Then** aparecem no Loki com labels `service`/`level` e cid como metadata/filtrável no texto.
3. **Given** dashboard provisionado, **When** operador filtra variável `cid`, **Then** o painel de logs restringe às linhas correspondentes.

---

### User Story 4 - Documentação operacional (Priority: P3)

Time encontra runbook, catálogo de métricas (`metricas.md`), seção no README e quickstart com PromQL/LogQL e troubleshooting.

**Why this priority**: Sustenta operação após a entrega.

**Independent Test**: Revisar links e exemplos sem secrets reais.

**Acceptance Scenarios**:

1. **Given** `metricas.md`, **When** o leitor busca uma métrica, **Then** encontra tipo, tags, nome Prometheus, PromQL e painel.
2. **Given** runbook, **When** target DOWN ou log não aparece, **Then** há passos de troubleshooting (host.docker.internal, arquivo não tailed, porta management).

### Edge Cases

- Redis indisponível no refresh de filas: manter último valor / NaN inicial; WARN rate-limited; não falhar o scrape.
- Lote parcialmente aceito: conta records/bytes aceitos; latency outcome = accepted.
- Falha de destination após auth: latency outcome = failed; sem incremento de accepted.
- Recusa (auth, rate, tamanho, quota, suspensão): latency outcome = refused; sem accepted.
- Cardinalidade: tags só dos conjuntos fechados aprovados; MeterFilter remove identidades.
- Management port não deve ser pública em produção (rede interna / bind localhost).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Sistema MUST expor scrape Prometheus na porta de management configurável (`MANAGEMENT_PORT`, default 9464) com `health`, `info` e `prometheus`.
- **FR-002**: Sistema MUST permitir scrape sem autenticação apenas nesses endpoints de management; demais Actuator sensíveis MUST NOT ser expostos; comportamento da porta principal MUST permanecer autenticado.
- **FR-003**: Sistema MUST publicar exatamente as cinco métricas Q4 com as tags aprovadas e sem tenantId/productId/token.
- **FR-004**: Contadores de aceite MUST incrementar somente após sink/publish bem-sucedido; bytes MUST reutilizar a medida canônica de volume/uso.
- **FR-005**: Timer de latência MUST cobrir o handling completo nos receivers HTTP e gRPC para todo outcome.
- **FR-006**: Gauges de fila/DLQ MUST refletir XLEN via refresh agendado (default 15s), só com Redis/messaging ativo, com fallback e WARN rate-limited em falha.
- **FR-007**: Sistema MUST aplicar MeterFilter removendo tags proibidas e tag comum `application`.
- **FR-008**: Stack local MUST incluir Prometheus, Grafana e Loki (+ Alloy) em compose separado `lhamalog-observability`, com datasources/dashboards versionados.
- **FR-009**: Logs do Collector MUST ir para Loki preservando o formato princípio VI; labels só baixa cardinalidade; painel filtrável por cid.
- **FR-010**: Documentação pt-BR MUST incluir `metricas.md`, runbook de observabilidade, README, quickstart e placeholders em `.env.example` (nunca `.env` real).
- **FR-011**: Makefile MUST oferecer `metrics-up|down|ps|logs` (e opcionalmente `metrics-config`).
- **FR-012**: Testes MUST cobrir cada meter novo (SimpleMeterRegistry), scrape `/actuator/prometheus`, segurança dos endpoints e filtro de tags.

### Key Entities

- **OperationalMetric**: nome, tipo (counter/timer/gauge), tags permitidas, momento de atualização.
- **ScrapeEndpoint**: porta/endereço de management, conjunto de endpoints expostos.
- **QueueDepthSample**: sinal, tamanho da stream principal e DLQ, timestamp do último refresh bem-sucedido.
- **LogShipperConfig**: caminho do arquivo de log, labels Loki, regex de parse princípio VI.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em ambiente local, 100% das cinco métricas aprovadas aparecem no scrape Prometheus após tráfego de exemplo.
- **SC-002**: Operador obtém p50/p95/p99 de latência de ingestão e taxa de aceite (records/s, bytes/s) no dashboard em menos de 5 minutos após subir o stack.
- **SC-003**: Busca por um correlation id conhecido no Grafana retorna as linhas de log correspondentes do Collector.
- **SC-004**: Nenhum teste ou scrape de exemplo inclui tenantId/productId/token como label/tag de métrica ou label Loki.
- **SC-005**: `mvnw verify` passa com a suíte verde incluindo os novos testes.

## Assumptions

- Branch `feature/LLT-187-collector-metrics` já existe; pasta da spec é criada manualmente (sem script que cria branch).
- Export OTLP de métricas permanece; Prometheus é adicional.
- Receivers OTLP não são Spring MVC; `http.server.requests` cobre sobretudo Actuator/MVC — documentado.
- Compose de observabilidade não depende dos secrets do compose principal.
- Parent agent fará o live check dos containers; esta entrega valida `compose config -q` e testes automatizados.
