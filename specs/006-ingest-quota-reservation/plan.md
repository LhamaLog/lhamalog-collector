# Plano de Implementação: Reserva de Cota para Ingestão (LLT-186)

**Branch**: `feature/LLT-186-tenant-product-volume` | **Data**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Entrada**: Especificação em `specs/006-ingest-quota-reservation/spec.md`.

## Resumo

Introduzir reserva no Core em paralelo à sanitização/ocultação no Collector. Depois de a
sanitização terminar, o Collector confirma a reserva e só então grava no OpenObserve e
publica no stream/DLQ. Uma reserva nunca confirmada expira em 20 minutos. A medição de uso
da spec 005 continua sendo a única fonte de bytes e contagem aceitos.

Após `confirm` bem-sucedido, falha de escrita/publicação é compensada por reembolso
idempotente solicitado pelo outbox Mongo durável do Collector.

## Contexto Técnico

**Linguagem/versão**: Java 25 nos dois serviços.

**Dependências principais**: Spring Boot 4.1.1, Spring Data MongoDB, OpenTelemetry Proto,
gRPC, HTTP do JDK/Spring e componentes existentes de Redis Streams no Collector.

**Armazenamento**: MongoDB no Core para assinatura, ciclo, saldo, reservas e outbox de
notificação; Redis já está configurado no Core, mas não há publicador de notificação
existente. Collector usa a área temporária e stream existentes.

**Testes**: JUnit 5, AssertJ, Mockito e Testcontainers; testes de contrato HTTP e gRPC,
integração Collector–Core e recuperação de expiração/falhas.

**Plataforma-alvo**: serviços Linux em contêiner; desenvolvimento em Windows.

**Tipo de projeto**: dois serviços Java — receptor OTLP (Collector) e API interna (Core).

**Metas de desempenho**: reserva ocorre concorrentemente à sanitização; o lote não é
gravado/publicado antes de `confirm`; não há SLO numérico definido nesta entrega.

**Restrições**: TTL fixo decidido de 20 minutos; tenant e produto são derivados do token;
data civil UTC anterior a `firstPaymentDate` não tem ciclo pago; não duplicar a medição
canônica da spec 005; nenhum segredo pode constar em código, teste ou documento.

**Escala/escopo**: reserva por lote elegível em bytes canônicos; logs e traces compartilham
saldo por tenant, produto e ciclo. Lote que excede o saldo é recusado integralmente.

## Verificação da Constituição

| Princípio | Situação antes do design | Situação após o design |
|---|---|---|
| I. SpecKit em toda feature | Esta nova feature possui spec, plan e gerará tasks antes de código. | Conforme |
| II. IA com revisão e domínio do desenvolvedor | Decisões conhecidas, sugestões e pendências estão documentadas. | Conforme |
| III. Testes antes do merge | O plano exige testes unitários, contrato, integração e recuperação. | Conforme |
| IV. Inglês no código, português na documentação | Artefatos humanos estão em pt-BR; nomes futuros de código serão em inglês. | Conforme |
| V. Segredos fora do código | Integração só referencia token de serviço sem valor persistido. | Conforme |

**Gates antes e após o design**: não há violação da constituição. A implementação pode
prosseguir.

## Fluxo Planejado

1. O receptor OTLP autentica o token do produto, identifica tenant, produto e domínio e
   avalia o lote como já ocorre hoje.
2. Registros vazios ou inteiramente inválidos não criam reserva. Para lote elegível, o
   Collector inicia em paralelo a sanitização e `POST /internal/usage-reservations`, com
   bytes canônicos recebidos antes da sanitização.
3. `reserve` usa token de serviço, `reservationId` estável, tenant, produto, bytes, lote e
   domínio. Atualização condicional do saldo evita ultrapassar a franquia; ela aumenta
   `reservedBytes`. `unlimitedUsage=true` autoriza sem débito.
4. Ao concluir a sanitização, somente `AUTHORIZED` ou `AUTHORIZED_UNLIMITED` permite
   `confirm`. A confirmação move `reservedBytes` para `consumedBytes` e é idempotente.
5. Só depois de `confirm` bem-sucedido o lote é gravado no OpenObserve e publicado no
   stream/DLQ. `reservationId` pode acompanhar a mensagem para rastreabilidade.
6. Se sanitização rejeitar/falhar após `reserve`, o Collector chama `release`. Reserva
   nunca confirmada expira em 20 minutos e devolve os bytes reservados.
7. Sem saldo, o lote temporário é descartado e o exporter recebe HTTP `429` ou gRPC
   `RESOURCE_EXHAUSTED`, com retry de 120 segundos. Sem plano e Core fora retornam HTTP
   `503` ou gRPC `UNAVAILABLE`, também com retry de 120 segundos; Core fora falha fechado.
8. Falha, timeout, 5xx ou `RESERVATION_NOT_ACTIVE` em `confirm` corta a execução, sem
   OpenObserve/stream/DLQ, e retorna `503`/`UNAVAILABLE`. Falha após débito e antes/depois
   da escrita/publicação enfileira reembolso idempotente no outbox; a resposta mantém
   `503`/`UNAVAILABLE` e o reenvio usa a mesma reserva determinística.
9. Após cada `confirm` que debita, o Core avalia cruzamento de 20% e 5%, registra
   idempotência Mongo e encaminha `SALDO_BAIXO` para uma porta/outbox de notificação.

## Contratos e Integrações

- [Contrato de reserva](contracts/reservatokens-api.md): operações idempotentes de reservar,
  confirmar e liberar; resultados de autorizado, sem saldo, sem plano/ciclo, expirada e falha.
- [Contrato da recusa OTLP](contracts/otlp-quota-rejection.md): mapeia o resultado interno
  à resposta HTTP protobuf, HTTP JSON e gRPC com retry de 120 segundos.
- [Contrato de evento `SALDO_BAIXO`](contracts/saldo-baixo-event.md): evento para o stream
  de notificação; entrega WEB/E-MAIL e matriz pertencem ao módulo externo.
- [Modelo de dados](data-model.md): descreve reserva, saldo de ciclo, lote temporário e
  vínculo com stream/fato de uso.
- [Integração existente de usage facts](../005-tenant-product-volume/contracts/usage-facts-api.md):
  preserva `X-LhamaLog-Service-Token`, fatos, ciclo e idempotência existentes; o evento
  pode propagar `reservationId` para rastreabilidade.

## Estados e Recuperação

| Componente | Estados planejados | Transições relevantes |
|---|---|---|
| Lote temporário | `PENDING_RESERVATION`, `AUTHORIZED`, `REJECTED`, `RELEASED`, `EXPIRED` | aguarda reserva; autorizado segue ao fluxo; recusado é removido; falha libera |
| Reserva no Core | `RESERVED`, `CONFIRMED`, `REFUNDED`, `RELEASED`, `EXPIRED` | reserva retém; confirm debita; refund CAS devolve consumo; release/TTL devolvem reserva |
| Outbox de reembolso | `PENDING`, `IN_FLIGHT`, `DONE`, `SUSPENDED` | único por reserva; retry com backoff/teto; `401`/`403` suspendem |
| Outbox de uso existente | `PENDING`, `ACCEPTED`, `SYNCED`, `ABANDONED`, `AMBIGUOUS`, `SUSPENDED` | não é substituída; fato deve ser correlacionável à reserva quando definido |
| Alerta de saldo | `PENDING`, `PUBLISHED` (outbox) | uma emissão por limiar, ciclo, tenant e produto |

## Estratégia de Testes

- Unitários no Core: bytes canônicos, transições idempotentes, UTC e
  `firstPaymentDate`, sem limite, sem plano, saldo insuficiente e expiração de 20 minutos.
- Unitários no Collector: concorrência de reserva/sanitização, confirmação antes de
  OpenObserve/stream, descarte em recusa, release antes de débito e propagação de identificador.
- Contrato: autenticação de serviço, reserve/confirm/release/refund e respostas sem segredos.
- Protocolos OTLP: HTTP protobuf, HTTP JSON e gRPC para autorização, sem saldo, sem plano e
  Core indisponível.
- Integração: ciclo/saldo no Core, Collector–Core, confirmação, release, TTL, `SALDO_BAIXO`
  e compatibilidade com `UsageFact`/reenvio `ABANDONED`.
- Recuperação: reinício entre reserva e confirmação sem consumo ou estorno duplicado; Core
  indisponível durante refund com retry posterior no outbox.
- Falha pós-débito: refund atômico, terminal em `409 RESERVATION_NOT_REFUNDABLE`, e
  `SALDO_BAIXO` sem reemissão.

## Estrutura do Projeto

### Documentação

```text
specs/006-ingest-quota-reservation/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── reservatokens-api.md
│   ├── otlp-quota-rejection.md
│   └── saldo-baixo-event.md
└── tasks.md
```

### Código previsto

```text
lhamalog-collector/
├── src/main/java/br/com/lhamalog/collector/
│   ├── ingestion/otlp/              # recepção, avaliação, área temporária e sinks
│   ├── messaging/                   # evento e stream
│   ├── usage/                       # facts/outbox existentes e cliente de reserva
│   └── config/                      # propriedades seguras
└── src/test/java/br/com/lhamalog/collector/
    ├── ingestion/otlp/
    └── usage/

lhamalog-core-llt186/
├── src/main/java/br/com/lhamalog/core/usage/
│   ├── api/                         # contrato interno de reservas
│   ├── application/                 # saldo, ciclo e transições
│   └── infrastructure/              # documentos e índices MongoDB
└── src/test/java/br/com/lhamalog/core/usage/
    ├── api/
    ├── application/
    └── infrastructure/
```

**Decisão de estrutura**: estender o módulo de uso existente nos dois repositórios, mantendo
o caminho de medição separado da autorização de saldo, mas com correlação explícita.

## Rastreio de Complexidade

Sem violações da constituição a justificar.
