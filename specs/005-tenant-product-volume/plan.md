# Plano de Implementação: Volume por Tenant e Produto (LLT-186)

**Branch**: `feature/LLT-186-tenant-product-volume` | **Data**: 2026-11-08 | **Spec**: [spec.md](spec.md)

**Entrada**: Especificação em `specs/005-tenant-product-volume/spec.md`

## Resumo

Medir fatos imutáveis por `tenantId`, `productId`, domínio (`LOGS` ou `TRACES`) e lote aceito. O
`usageFactId` é `SHA-256(tenantId, productId, domain, batchId)` e a evidência Redis é sempre
`usage:fact:{usageFactId}`, independente da rota. `batchId` é
`SHA-256(tenantId, productId, domain, lista ordenada dos recordIds aceitos)`. `RecordIds` atual deriva cada
`recordId` do conteúdo
protobuf de resource, scope e registro, mais tenant/produto e ocorrência; isso deve ser preservado/validado
para que JSON/protobuf, HTTP/gRPC e compressão produzam o mesmo resultado.

MongoDB mantém o candidato imutável `PENDING` e CAS para `ACCEPTED`, `ABANDONED`, `AMBIGUOUS` e
`SYNCED`. Após OpenObserve, grava-se `PENDING` com *write concern* `majority`; Lua faz ausente →
`CLAIMED:{token}` → `XADD` → `DONE:{route}:{entryId}`. `DONE` não faz novo `XADD`; `ABANDONED` retorna
indisponibilidade. Erro no `XADD`, ou no `SET DONE` depois de `XADD`, deixa `CLAIMED`; no segundo caso
há entrada existente e o fato é `AMBIGUOUS`, nunca faturável ou abandonado automaticamente. Falha ao
gravar `PENDING` retorna `503`; falha ao CAS `ACCEPTED` após `DONE` retorna `200` e é reconciliada.
Somente `ACCEPTED` é sincronizável; `SYNCED` significa confirmação 2xx pelo Core.

## Technical Context

**Linguagem/versão**: Java 25

**Dependências principais**: Spring Boot 4.1.1, Spring Data MongoDB, Spring Data Redis, Jackson 3,
OpenTelemetry Proto, gRPC e cliente HTTP do JDK/Spring a selecionar sem nova biblioteca.

**Armazenamento**: OpenObserve (dados de telemetria, existente), Redis Streams/DLQ (aceitação existente) e
MongoDB (coleção de outbox de uso proposta).

**Testes**: JUnit 5, AssertJ, Mockito e Testcontainers (MongoDB/Redis); testes de contrato HTTP simulam Core.

**Plataforma-alvo**: serviço Linux em contêiner; desenvolvimento em Windows.

**Tipo de projeto**: serviço web receptor OTLP.

**Metas de desempenho**: não introduzir chamada ao Core no caminho síncrono do exportador; gravar candidato
no Mongo com `majority` antes da publicação Redis e usar uma única operação atômica Redis para `XADD` e
evidência. A sincronização da outbox é assíncrona e configurável.

**Restrições**: bytes são a serialização protobuf determinística de `ExportLogsServiceRequest` ou
`ExportTraceServiceRequest` reconstruída exclusivamente com registros válidos, preservando ordem e
hierarquia de resource/scope/envelope e removendo grupos vazios. JSON e protobuf, HTTP e gRPC convergem à
mesma mensagem; compressão e metadados internos não contam. Não há canonicidade entre lotes reagrupados:
o overhead pode variar. Logs/traces não se misturam; GB = bytes / 1.000.000.000. Sem promessa de
exactly-once, sem faturar metadados internos e sem implementar cota LLT-183.

**Escala/escopo**: um fato por lote aceito e domínio. A garantia de durabilidade é condicional à configuração
Redis já implantada: esta feature não altera `appendfsync`, não exige Redis Cluster e não promete
exactly-once. Com AOF `appendfsync everysec` padrão, uma queda após `200` pode perder até ~1 s de stream e
chave; o protocolo Lua sem rollback (`CLAIMED`/`DONE`/`ABANDONED`/`AMBIGUOUS`) permanece e pode resultar em
subcontagem. `appendfsync always` reduz essa janela, com custo de latência, mas é decisão operacional de
infraestrutura pendente e não será aplicada por esta feature. A chave de deduplicação expira após sete dias;
depois disso, reenvio idêntico pode ser contado novamente. Um lote novo e diferente conta integralmente,
inclusive registros já medidos e envelope; a deduplicação é pelo lote (`batchId`/`usageFactId`), não por
`recordId`. A autenticação é token de serviço dedicado por configuração/segredo de ambiente, via
`X-LhamaLog-Service-Token`, nas rotas internas do contrato.

## Verificação da Constituição

| Princípio | Situação antes da pesquisa | Situação após o design |
|---|---|---|
| I. SpecKit em toda feature | Este plano gera os artefatos exigidos; `tasks.md` ficará para a próxima fase. | Conforme |
| II. IA com revisão e domínio do desenvolvedor | Decisões, limites e escolhas técnicas ficam registradas nestes artefatos. | Conforme |
| III. Testes antes do merge | O plano exige TDD para duplicação, reinício, falhas parciais, Core offline e os quatro transportes OTLP. | Conforme |
| IV. Inglês no código, português na documentação | Nomes de código/contrato usam inglês; documentação está em pt-BR. | Conforme |
| V. Segredos fora do código | Tokens vêm somente de configuração/segredo de ambiente; nenhum valor, inclusive de teste, é versionado. | Conforme |

**Gates pendentes — operacionais**: a rotação do token dedicado e a eventual adoção de
`appendfsync always` são decisões de infraestrutura, não bloqueios de design. A criação no Core da nova
entidade de plano/assinatura e o preenchimento externo obrigatório de `firstPaymentDate` são dependências
para aplicar os ciclos definidos em [RF-006 e RF-014](spec.md#requisitos-funcionais); UTC é o default técnico para converter `acceptedAt` em data civil.
Os riscos condicionais de durabilidade devem ser aceitos operacionalmente antes da produção, sem enfraquecer
persistência do fato, retry, idempotência ou a confirmação ao exportador quando o Core estiver indisponível.

**Validação documental**: o analyze anterior não foi integralmente validado porque o script de pré-requisito
permaneceu bloqueado. Não foi executado novo analyze, script ou teste nesta atualização; este plano não
libera implementação.

**Requisito novo a reportar**: a implementação exige chave Redis de evidência, script Lua/operação Redis
atômica e reconciliador de inicialização e periódico. Isso é necessário para fechar o crash entre Redis e
Mongo; não está presente na especificação original como componente explícito.

**Escolhas técnicas de integração**: o Collector lê `LHAMALOG_COLLECTOR_CORE_TOKEN` e o Core lê
`LHAMALOG_CORE_USAGE_TOKEN`, exclusivamente por configuração/segredo de ambiente; testes injetam valor
efêmero. O Collector envia `X-LhamaLog-Service-Token: <token>` no `POST /internal/usage-facts`. O Core
oferece ainda `GET /internal/usage-facts?tenantId&productId&from&to`, com janela `[from,to)`, e
`GET /internal/usage-facts/current-cycle?tenantId&productId`, agregado para o ciclo vigente definido em [RF-006 e RF-014](spec.md#requisitos-funcionais).

## Project Structure

### Documentation (this feature)

```text
specs/005-tenant-product-volume/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/
    └── usage-facts-api.md
```

### Source Code (repository root)
```text
src/main/java/br/com/lhamalog/collector/
├── ingestion/otlp/
│   ├── application/                 # identifica o subconjunto aceito e coordena sinks
│   └── sink/                        # OpenObserve, Stream/DLQ e serialização OTLP canônica
├── usage/
│   ├── domain/                      # UsageFact e cálculo de bytes
│   ├── outbox/                      # documento MongoDB e repositório
│   └── core/                        # cliente HTTP e trabalhador de retry
└── config/                          # propriedades e beans

src/test/java/br/com/lhamalog/collector/
├── ingestion/otlp/                  # HTTP/gRPC, logs/traces e lote parcial
└── usage/                           # unidade, Mongo/Testcontainers e contrato Core
```

**Decisão de estrutura**: adicionar o pacote `usage` para não acoplar a durabilidade e o envio ao Core ao
modelo de mensageria Redis. A integração é disparada no ponto em que `PublishingLogsSink` /
`PublishingTracesSink` já conhecem que OpenObserve e Stream/DLQ confirmaram.

## Rastreio de Complexidade

Sem violações da constituição a justificar.

## Pendências para a próxima implementação

Registrar e tratar numa entrega seguinte, sem reabrir o escopo desta:

> **Nota histórica — 2026-11-10 (America/Sao_Paulo):** a restrição deste plano de não
> implementar cota descreve a entrega histórica da medição. Michel decidiu nesta data que a
> recusa sem saldo/ciclo pago será planejada na mesma LLT-186 em
> `specs/006-ingest-quota-reservation`; o desenho ainda não foi implementado.
> **Atualização histórica — 11/11/2026:** spec006 inclui refund idempotente por outbox após
> falha pós-débito; a identidade e os bytes canônicos de medição deste plano permanecem a
> referência, sem duplicação.

- Ensaio E2E T046 (Collector + Core juntos): executar e registrar evidências nesta entrega.
- Garantia de durabilidade condicional ao Redis AOF `everysec` (RPO de ~1 s após HTTP 200). `appendfsync always` permanece decisão operacional não tomada. Lua sem rollback pode deixar `AMBIGUOUS`. Redis Cluster não é suportado.
- Fonte externa de `firstPaymentDate` indefinida.
- Rotação operacional (política) de `LHAMALOG_COLLECTOR_CORE_TOKEN` / `LHAMALOG_CORE_USAGE_TOKEN`. O runbook técnico de rotação já está documentado; a política em si não foi decidida.
- Os 4 `CompanyProductIntegrationTests` do Core seguem dependentes de `TEST_MONGODB_URI` no verify.
- Confirmação do Michel: escolha técnica reversível de não haver ciclo ativo antes de `firstPaymentDate` (sem devolver ciclo futuro). Não altera a pendência da fonte da data.

A revisão do PR #4 do Core (TTL vs agregação, resumo por domínio, `Instant`, índices explícitos, duplicate key 11000, paginação, mapper e InMemory em teste) foi aplicada no worktree local e registrada como T052–T061.

A revisão do PR #5 do Collector (idempotência sem `acceptedAt`, reabertura de `ABANDONED`, CAS do lease, TTL de 7 dias, `SUSPENDED`, `JsonMapper`, Lua/`CLAIMED`, 11000, no-ops e documentação da medição) foi aplicada no worktree local e registrada como T062–T070.
