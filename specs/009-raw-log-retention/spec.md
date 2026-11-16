# Feature Specification: Retenção de logs brutos por plano (streams OpenObserve por tenant)

**Feature Branch**: `009-raw-log-retention`

**Created**: 2026-11-16

**Status**: Clarified — 16/11/2026 (decisões do Michel)

**Input**: User description: "Streams OpenObserve por tenant com retenção `data_retention` conforme plano (retencaoDias / retentionDays) na resposta de reserva do Core; ADR_168; sem migração automática do stream compartilhado."

**Jira**: LLT-185 — retenção de logs brutos por plano. Card original menciona MongoDB/Elasticsearch; **seguimos ADR_168 (OpenObserve)** + decisão do Michel.

## Fontes

- **ADR_168**: destino de ingestão = OpenObserve (substitui Elasticsearch).
- **ADR_131 (TabelaPrecificacao)**: campo `retencaoDias` — FREE 7, STARTER 15, PRO 30, BUSINESS 90, ENTERPRISE 90.
- **spec006 / LLT-186**: reserva `POST /internal/usage-reservations`; Collector já tolera campos extras.
- **spec007 / LLT-183**: limites de taxa/tamanho; fluxo auth → taxa → tamanho → reserva → sink.
- **Decisões do Michel em 16/11/2026**: Q1 stream por tenant; Q2 retenção via resposta de reserva + PUT settings.

## Separação de escopo

| Área | Cobre | Não cobre |
|------|-------|-----------|
| **Core (paralelo, outro agente)** | Expor `retentionDays` na resposta de reserva autorizada; valor = máximo `retencaoDias` entre assinaturas ACTIVE/DELINQUENT do tenant (sem plano = FREE 7) | UI de planos; cobrança |
| **Collector (este repo)** | Stream OO por tenant; gravar lote no stream do tenant; aplicar retenção via settings com cache; publicar referência Redis com stream do tenant | Migração automática do stream compartilhado `logs`/`traces`; alterar schemaVersion Redis |

## Clarifications

### Sessão 2026-11-16 (decisões do Michel)

Registro: **Decidido pelo Michel em 16/11/2026**.

- **Q1 — isolamento de stream**: um stream OpenObserve **por tenant** (substitui o stream compartilhado `logs` / `traces` na org `lhamalog`). Logs e traces usam streams distintos por sinal, ambos derivados do tenant.
- **Q2 — retenção**: Core envia `retentionDays` (nome em inglês no fio; mapeia ADR_131 `retencaoDias`) na resposta de reserva que **autoriza** ingestão (inclui unlimited e overage). Collector aplica `PUT /api/{org}/streams/{stream}/settings?type=logs|traces` com `{"data_retention": N}` quando o stream é novo ou N mudou, com cache para não chamar a cada lote.
- **Backend de armazenamento**: ADR_168 + Michel — OpenObserve, não Elasticsearch/Mongo para logs brutos.
- **Agregação multi-produto**: valor de retenção = **máximo** `retencaoDias` entre as assinaturas consideráveis do tenant (confirmado pelo Michel em 16/11/2026 16:36, ver sessão abaixo).

### Sessão 2026-11-16 16:36 (decisões do Michel sobre as 5 perguntas da LLT-185)

Registro: **Decidido pelo Michel em 16/11/2026 às 16:36** (repassado pelo BimBot Escola de TI).

1. **Tenant com vários planos**: usa a **maior** retenção entre as assinaturas consideráveis do tenant (já implementado).
2. **Assinatura antiga sem plano** (`planTier` ausente): passa a contar como **FREE = 7 dias**. Não fica mais com a retenção global do OpenObserve. O documento não é regravado: só o cálculo assume FREE. Assim o Core sempre envia `retentionDays` quando o tenant tem ao menos uma assinatura considerável.
3. **Downgrade**: a retenção menor vale imediatamente (o OpenObserve apaga o que passar do novo prazo); sem carência (já implementado).
4. **Status** (nomes em inglês desde o merge do Core #11; o Core ainda lê `ATIVA`/`INADIMPLENTE`/`SUSPENSA` gravados): ACTIVE conta; DELINQUENT só dentro da carência (`lhamalog.subscription.delinquency-grace`, padrão 15 dias, mesma regra da reserva: vencida a partir de `delinquentSince + carência`, inclusive) conta. SUSPENDED e DELINQUENT com a carência vencida **não** contam, porque a reserva já trata as duas como suspensas. Ajuste da review do BimBot (16/11/2026 17:36): antes a DELINQUENT contava mesmo depois da carência. Ex.: BUSINESS inadimplente há 30 dias + FREE ativa → 7 dias.
5. **Nome do campo**: `retentionDays` no fio (mapeia `retencaoDias` da ADR_131) (já implementado).

Consequência no Collector: nenhuma mudança de código. O ramo "sem `retentionDays` → não altera o stream" continua para **Core antigo** (leitor tolerante), **quota desligada** e tenant **sem nenhuma assinatura considerável** (só SUSPENDED ou nenhuma) — nesses casos vale a retenção global `ZO_COMPACT_DATA_RETENTION_DAYS`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Gravar lote no stream do tenant (Priority: P1) 🎯 MVP

Cada lote OTLP autenticado de logs (e traces) é gravado no stream OpenObserve determinado pelo tenant, não no stream compartilhado. Os documentos mantêm `tenantId` e `productId`. A mensagem Redis da Varredura referencia o stream do tenant.

**Why this priority**: Sem isolamento por tenant a retenção por plano não pode ser aplicada por stream.

**Independent Test**: Ingerir dois tenants distintos; conferir documentos em streams distintos e que a referência Redis traz o nome do stream do tenant.

**Acceptance Scenarios**:

1. **Given** tenant A autenticado, **When** envia logs válidos autorizados, **Then** os documentos ficam no stream derivado de A (prefixo configurável + sanitização), não em `logs` compartilhado.
2. **Given** tenant B distinto de A, **When** envia logs, **Then** o stream de B é diferente do de A (incluindo casos em que a sanitização colidiria sem sufixo de hash).
3. **Given** lote de traces, **When** aceito, **Then** grava no stream de traces do tenant (`type` traces na aplicação de settings).
4. **Given** lote aceito, **When** a referência é publicada no Redis, **Then** o campo OpenObserve `stream` carrega o stream do tenant (sem bump de schemaVersion).

---

### User Story 2 - Aplicar retenção do plano no stream (Priority: P1)

Quando a reserva autoriza e traz `retentionDays` válido (> 0), o Collector garante que o stream do tenant tenha `data_retention` igual a N. Falha de settings **não** recusa o lote já gravado.

**Why this priority**: Entrega o valor de negócio do plano (FREE 7 … ENTERPRISE 90).

**Independent Test**: Dois tenants com retenção 7 e 30; após ingestão, schema/settings do stream refletem N; simular falha de settings e confirmar lote aceito.

**Acceptance Scenarios**:

1. **Given** reserva AUTHORIZED com `retentionDays=7`, **When** o lote é gravado com sucesso, **Then** settings do stream logs (e traces se aplicável) ficam com `data_retention=7` (na primeira vez ou quando N muda).
2. **Given** cache já com (tipo, stream) → N, **When** novo lote com o mesmo N, **Then** settings não é chamado de novo (até expirar TTL do cache).
3. **Given** N muda (ex.: upgrade de plano), **When** chega reserva com novo N, **Then** settings é reaplicado e o cache atualiza.
4. **Given** `retentionDays` ausente, null ou ≤ 0, **When** lote autorizado, **Then** grava no stream do tenant **sem** alterar settings (retenção global OO permanece).
5. **Given** quota desligada (sem reserva), **When** lote aceito, **Then** grava no stream do tenant sem settings.
6. **Given** PUT settings falha, **When** lote já foi confirmado no OO, **Then** resposta OTLP de sucesso permanece; WARN + métrica failed; não cacheia sucesso; backoff negativo (~60 s) antes de nova tentativa.

---

### User Story 3 - Contrato Core e documentação operacional (Priority: P2)

O fio compartilhado `retentionDays` está documentado; o Core (outro repo) preenche o campo; runbook descreve transição do stream antigo, métricas e verificação.

**Why this priority**: Habilita o outro agente e a operação; não bloqueia o MVP de escrita por tenant.

**Independent Test**: Contratos em `contracts/` batem com o parsing do Collector; runbook lista passos de UNION/consulta e exclusão manual do stream legado.

**Acceptance Scenarios**:

1. **Given** resposta Core antiga sem `retentionDays`, **When** Collector parseia, **Then** trata como desconhecido e não altera settings.
2. **Given** operadores na transição, **When** consultam o runbook, **Then** sabem consultar stream legado + stream por tenant e quando apagar o legado.

### Edge Cases

- TenantId com maiúsculas, hífens ou caracteres fora de `[a-z0-9]` → sanitização + hash de 8 hex quando sanitização altera ou trunca.
- Truncamento longo (>40 chars no segmento sanitizado) → hash para evitar colisão.
- Dois tenantIds que sanitizam para a mesma string → streams distintos via hash.
- Settings antes do stream existir: aplicar **após** ingest bem-sucedida (stream já criado pelo `_json`).
- Falha intermitente de settings: backoff negativo; próximo lote após backoff tenta de novo.
- Outcome não autorizado: pode omitir `retentionDays`; Collector não aplica.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Collector MUST gravar cada lote de logs no stream OpenObserve determinístico do tenant (prefixo `OPENOBSERVE_LOGS_STREAM` / default `logs`).
- **FR-002**: Collector MUST gravar cada lote de traces no stream determinístico do tenant (prefixo `OPENOBSERVE_TRACES_STREAM` / default `traces`).
- **FR-003**: Nomes de stream MUST ser seguros para OpenObserve: minúsculos `[a-z0-9_]`, sem dado pessoal além do id já usado na plataforma, e colisão-seguros (hash SHA-256 quando sanitização altera/trunca).
- **FR-004**: Documentos MUST continuar com `tenantId` e `productId` no topo.
- **FR-005**: Referência Redis (`TelemetryIngestedBody.openobserve.stream`) MUST carregar o stream do tenant; mudança só de valor (sem schemaVersion bump).
- **FR-006**: Collector MUST parsear `retentionDays` opcional na resposta de reserva (ausente/null/≤0 → desconhecido).
- **FR-007**: Após escrita bem-sucedida, se `retentionDays` conhecido, Collector MUST garantir settings `data_retention=N` quando cache não tem (tipo, stream)→N; reaplicar se N mudou.
- **FR-008**: Aplicação de settings MUST NOT falhar nem atrasar materialmente a resposta OTLP do lote (async com correlation id, ou inline com timeout curto — documentar escolha).
- **FR-009**: Falha de settings MUST NOT perder/recusar o lote; MUST logar WARN `event=openobserve.stream_retention.apply_failed`; MUST NOT cachear falha como sucesso; MUST usar backoff negativo (~60 s).
- **FR-010**: Métrica counter `lhamalog.openobserve.retention.apply{outcome=applied|failed,type=logs|traces}` (sem tag de tenant).
- **FR-011**: Sem quota / sem `retentionDays` → stream do tenant, sem settings.
- **FR-012**: Migração do stream compartilhado legado = **somente documentação** (runbook).
- **FR-013** **(Core)**: Core MUST incluir `retentionDays` (inteiro) em respostas que autorizam ingestão, com valor = máximo `retencaoDias` das assinaturas do tenant com plano (recomendação até confirmação).
- **FR-014**: Testes unitários e de integração MUST cobrir naming, cache/backoff, parsing e ingestão com OpenObserve Testcontainers (ou stub se inviável).

### Key Entities

- **TenantStreamName**: nome derivado de prefixo de sinal + tenantId sanitizado (+ hash opcional).
- **StreamRetentionTarget**: (signalType, streamName) → retentionDays desejado e estado de cache/backoff.
- **ReservationResponse**: outcome + `retentionDays` opcional.
- **OpenObserveRef** (Redis): organization, stream (agora por tenant), batchId, etc.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% dos lotes aceitos de dois tenants distintos ficam em streams OpenObserve diferentes verificáveis por busca.
- **SC-002**: Após ingestão com `retentionDays` 7 e 30, a consulta de schema/settings dos streams respectivos mostra `data_retention` correspondente em até 30 s no ambiente de teste.
- **SC-003**: Com settings indisponível, ≥ 99% dos lotes já gravados no OO ainda retornam aceite OTLP (falha de settings não vira recusa).
- **SC-004**: Operador consegue, só com o runbook, consultar legado + stream novo e saber critério para apagar o legado.
- **SC-005**: Collectors antigos ignoram `retentionDays`; Cores antigos omitem o campo sem quebrar o Collector novo.

## Assumptions

- OpenObserve v1.1.2 (imagem já usada nos Testcontainers/compose).
- Org continua `lhamalog` (configurável).
- Prefixo de stream reutiliza `OPENOBSERVE_LOGS_STREAM` / `OPENOBSERVE_TRACES_STREAM` (passam a ser prefixos, não nome absoluto).
- Core entrega `retentionDays` em paralelo; Collector tolera ausência.
- Retenção global `ZO_COMPACT_DATA_RETENTION_DAYS` cobre o caso sem settings.
- Segmento sanitizado do tenant truncado em 40 caracteres antes do sufixo de hash.

## Out of Scope

- Migração/reindex automática do stream compartilhado.
- UI de escolha de retenção.
- Mudança de schemaVersion da mensagem Redis.
- Edição do repositório Core neste worktree.
