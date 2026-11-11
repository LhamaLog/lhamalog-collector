# Como o Collector mede o volume (LLT-186)

Este texto descreve o que o código faz hoje para medir o volume aceito de logs e traces
por tenant e produto, e como o Core agrega esses fatos no ciclo do plano.

Nenhum número de bytes abaixo veio de um teste que imprima o tamanho exato: os testes
`CanonicalLogsMeasurementTest` e `UsageFactFactoryTest` só garantem que
`serializedBytes()` é positivo para o envelope OTLP protobuf reconstruído (resource,
scope e envelope incluídos). Os valores em bytes e GB marcados como **EXEMPLO** são
ilustrativos e consistentes com as regras; não são medição de produção.

## O que entra na conta

1. Só registros **aceitos** (token válido e avaliação do registro ok) entram no fato.
2. Logs e traces são **domínios separados**. Um lote de logs nunca se mistura com spans.
3. Tenant e produto vêm do token do produto, não do payload OTLP.
4. A **contagem** é o número de registros aceitos do lote (`acceptedRecordCount`).
5. Os **bytes** são o tamanho do lote aceito serializado em OTLP protobuf canônico,
   descomprimido, incluindo resource, scope e envelope. Não se usa o tamanho do JSON HTTP
   nem o corpo comprimido na rede.
6. 1 GB decimal = `1_000_000_000` bytes (`acceptedBytes / 1e9`). O Collector envia bytes;
   o Core calcula o GB na consulta do ciclo.
7. A chave de idempotência `usageFactId` é `SHA-256(tenant, produto, domínio, batchId)`,
   e `batchId` é `SHA-256(tenant, produto, domínio, ids aceitos ordenados)`. O instante
   `acceptedAt` **não** entra nessa chave nem na comparação de reenvio.
8. Reenvio idêntico (mesmo conteúdo imutável do lote) dentro de 7 dias **não** conta de
   novo. Um lote **novo e diferente** conta inteiro, mesmo que traga registros já medidos
   em outro lote; não há deduplicação por `recordId`.
9. Um lote **ABANDONED** nunca foi contado. O reenvio pode ser reaberto e medido **uma**
   vez. Depois de 7 dias (RF-012), um `SYNCED` (ou `ABANDONED` terminal não reaberto)
   pode expirar e o mesmo lote pode ser medido de novo.
10. O Core guarda a idempotência de 7 dias em `UsageFactIdempotency` (com TTL) e os fatos
    de agregação em `UsageFact` (sem TTL). `GET /internal/usage-facts/current-cycle`
    devolve `logs` e `traces` separados para o ciclo vigente do tenant/produto.

> **Nota histórica — 2026-11-10 (America/Sao_Paulo):** o parágrafo original afirmava que a
> LLT-186 só media e agregava, sem bloqueio por cota. Na mesma data Michel decidiu incluir a
> recusa sem saldo ou sem ciclo pago na própria LLT-186; o desenho vive em
> [specs/006-ingest-quota-reservation](../specs/006-ingest-quota-reservation/).

> **Atualização — 2026-11-11:** a reserva/débito da spec006 está implementada atrás de
> `lhamalog.quota.enabled` (padrão `false`). Com a flag desligada, o comportamento da
> spec005 permanece inalterado.

## Exemplo 1 — lote com vários registros (EXEMPLO)

Situação ilustrativa: o produto `app-checkout` do tenant `acme` envia um lote HTTP JSON
com 3 logs aceitos e 1 log rejeitado (timestamp fora da janela). O fato considera só os 3
aceitos.

| Campo | Valor (EXEMPLO) |
|---|---|
| Domínio | `LOGS` |
| Registros aceitos | 3 (`log-a`, `log-b`, `log-c`) |
| Bytes canônicos do envelope reconstruído com os 3 logs | 1 543 |
| GB decimal | 1 543 / 1_000_000_000 = 0,000001543 |

Se o mesmo tenant enviar no mesmo segundo um lote de 2 spans aceitos com 820 bytes
canônicos, isso vira **outro** fato no domínio `TRACES`, sem se somar à contagem de logs.

Reenvio idêntico desse lote de 3 logs no mesmo dia: o Collector reconhece o mesmo
`usageFactId` (ignorando um `acceptedAt` novo) e não cria outra agregação. O Core responde
`200` e o ciclo permanece com 3 logs e 1 543 bytes.

Um lote novo com `log-a`, `log-b`, `log-c` e `log-d` (4 registros, 2 100 bytes de EXEMPLO)
é outra chave. Ele soma **integralmente** 4 registros e 2 100 bytes, inclusive os três
já medidos e o envelope novo.

## Exemplo 2 — envio singular, um registro (EXEMPLO)

Um único span aceito, enviado por gRPC, sem outros spans no lote.

| Campo | Valor (EXEMPLO) |
|---|---|
| Domínio | `TRACES` |
| Registros aceitos | 1 (`span-1`) |
| Bytes canônicos (resource + scope + envelope + o span) | 256 |
| GB decimal | 256 / 1_000_000_000 = 0,000000256 |

O transporte (gRPC, HTTP protobuf ou HTTP JSON) não muda o tamanho medido: a conta usa o
mesmo envelope protobuf canônico. Um reenvio idêntico desse span único não altera o ciclo.


## Retomar a outbox depois de um 401/403 (token)

O worker trata HTTP 401 e 403 como falha de autenticacao permanente: o item vai para
`SUSPENDED` com `lastErrorCode` `HTTP_401` ou `HTTP_403`. O polling so busca documentos
`ACCEPTED` com `nextAttemptAt <= agora`, entao um item suspenso nao e reenviado sozinho.
A suspensao nao grava data invalida em `nextAttemptAt`.

Depois de corrigir o token do Collector e do Core (mesmo valor, sem espaco, aspas ou
quebra de linha; os dois lados fazem trim na carga), retome os itens suspensos no Mongo
do Collector:

```js
db.usage_outbox.updateMany(
  { state: "SUSPENDED", lastErrorCode: { $in: ["HTTP_401", "HTTP_403"] } },
  { $set: {
      state: "ACCEPTED",
      lastErrorCode: null,
      nextAttemptAt: new Date(),
      leaseOwner: null,
      leaseUntil: null
    }
  }
)
```

O worker volta a arrendar esses documentos no proximo tick. Nao e preciso apagar fatos
nem a colecao de idempotencia do Core: um reenvio identico continua idempotente.

Retries transitorios (429, 5xx, rede) permanecem em `ACCEPTED`, com `nextAttemptAt` limitado
pelo teto de backoff e pelo intervalo de data BSON (`1970-01-01` ate `9999-12-31`).

## Bytes medidos no ensaio T046 (2026-11-10, America/Sao_Paulo)

Valores lidos da outbox do Collector (`usage_outbox.fact.acceptedBytes`) e confirmados no Core
(`GET /internal/usage-facts` e `GET /internal/usage-facts/current-cycle`) no ensaio ponta a ponta.
O worker POST em `/internal/usage-facts` com `Content-Type: application/json` e apenas o header
`X-LhamaLog-Service-Token` (sem `Authorization`). Fingerprints do token (tamanho + SHA-256)
bateram entre o script, o Collector e o Core; o Core respondeu 201 e nao logou `Rejected`.

| Lote | Dominio | Registros aceitos | Bytes canonicos | Destino |
| --- | --- | ---: | ---: | --- |
| A1 (HTTP JSON) | LOGS | 1 | 74 | tenant-e2e-a / product-e2e-a |
| reenvio identico de A1 | LOGS | (mesmo fato) | (nao duplica) | Core manteve um unico item de 74 bytes |
| lote parcial (1 registro valido) | LOGS | 1 | 82 | tenant-e2e-a / product-e2e-a |
| lote novo A1+A2 | LOGS | 2 | 98 | tenant-e2e-a / product-e2e-a (conta o lote inteiro) |
| A via gRPC | LOGS | 1 | 66 | tenant-e2e-a / product-e2e-a |
| B (HTTP JSON, 1 span) | TRACES | 1 | 95 | tenant-e2e-b / product-e2e-b |
| C (HTTP JSON, Core fora e depois de volta) | LOGS | 1 | 79 | tenant-e2e-a / product-e2e-a, outbox SYNCED apos retry |

Agregado no Core apos o retry do lote C (ciclo ativo, datas em UTC):

- tenant-e2e-a / product-e2e-a, plano mensal `2026-11-01T00:00:00Z`–`2026-12-01T00:00:00Z`:
  logs 6 registros e 399 bytes (`74+82+98+66+79`); traces 0.
  `acceptedGigabytes` = `3.99E-7` (`399 / 1_000_000_000`).
- tenant-e2e-b / product-e2e-b, plano semanal `2026-11-08T00:00:00Z`–`2026-11-15T00:00:00Z`:
  traces 1 registro e 95 bytes (`acceptedGigabytes` = `9.5E-8`); logs 0.

Os 5 fatos anteriores ao lote C ja estavam `SYNCED` com `nextAttemptAt` em ISODate valida
(sem `Invalid Date`). O lote C ficou `SYNCED` depois que o Core voltou (`lastErrorCode=NETWORK`
somente nesse item, `attemptCount=4`, data valida).

## Exemplo 3 — “limite do plano atingido” (histórico spec005)

> **Superseded — 2026-11-11:** o bloco abaixo descreve somente a entrega da medição
> (spec005 / T046, 2026-11-10). Com `lhamalog.quota.enabled=true`, a spec006 passa a
> reservar/debitar e a recusar ingestão sem saldo ou sem ciclo pago. Ver
> [quota-reservation-runbook.md](quota-reservation-runbook.md) e a seção seguinte.

> **Aviso histórico — 2026-11-10 (America/Sao_Paulo):** até a entrega da medição
> (spec005 / T046), o código realmente não recusava ingestão por limite. Nesta data, Michel
> decidiu incluir na LLT-186 a recusa sem saldo ou sem ciclo pago. O desenho está em
> [`specs/006-ingest-quota-reservation`](../specs/006-ingest-quota-reservation/).

Revisto no código dos dois repositórios em 2026-11-10: **não havia bloqueio, recusa, HTTP de
cota nem aviso automático quando o uso passava de um limite de plano**. A ingestão OTLP não
consultava franquia. O Core não comparava `current-cycle` com um teto contratual.

O que existia (e permanece) é a **leitura** do uso no ciclo vigente, separado por domínio.
Suponha (EXEMPLO) um plano mensal cujo ciclo atual vai de `2026-11-01T00:00:00Z` a
`2026-12-01T00:00:00Z` e estes fatos já persistidos:

- logs: 12 000 registros, 2 500 000 000 bytes
- traces: 800 registros, 400 000 000 bytes

`GET /internal/usage-facts/current-cycle?tenantId=acme&productId=app-checkout` devolve
algo nesta forma:

```json
{
  "active": true,
  "from": "2026-11-01T00:00:00Z",
  "to": "2026-12-01T00:00:00Z",
  "logs": {
    "acceptedRecordCount": 12000,
    "acceptedBytes": 2500000000,
    "acceptedGigabytes": 2.5
  },
  "traces": {
    "acceptedRecordCount": 800,
    "acceptedBytes": 400000000,
    "acceptedGigabytes": 0.4
  }
}
```

Se a data civil UTC ainda for anterior a `firstPaymentDate`, o Core devolve
`active: false` e totais zerados (escolha técnica reversível, para o Michel confirmar).
Com a flag de cota desligada, isso também não bloqueia a ingestão.

A reserva/débito **não** cria uma segunda forma de contar bytes: `requestedBytes` é igual a
`acceptedBytes` canônicos da spec005.

## Reserva de cota e débito (spec006)

> **Comportamento vigente — 2026-11-11:** quando `lhamalog.quota.enabled=true`, a ingestão
> elegível reserva, confirma e (se necessário) reembolsa via Core. As afirmações históricas
> de que a ingestão nunca é bloqueada descrevem somente a entrega spec005.

Para lote elegível, o Collector calcula os bytes OTLP canônicos do lote recebido (antes da
sanitização/ocultação) e inicia reserva no Core em paralelo à sanitização. Depois de sanitizar,
faz `confirm` **antes** de escrever no OpenObserve ou publicar no stream/DLQ. Saldo
insuficiente retorna `429`, sem plano ou Core indisponível retorna `503`; todos usam
`Retry-After: 120` e o lote não é escrito nem enfileirado.

Se a escrita/publicação falhar depois do débito, o Collector mantém a resposta indisponível e
persiste um refund idempotente em outbox Mongo (`quota_refund_outbox`). O Core devolve consumo
apenas uma vez e o refund não reemite `SALDO_BAIXO`; esses alertas ocorrem uma vez em 20% e
uma vez em 5% por ciclo.

Runbook operacional (recusa, Core fora, refund `SUSPENDED`, `SALDO_BAIXO` com Redis
desligado): [quota-reservation-runbook.md](quota-reservation-runbook.md).

### Métricas (Collector)

Contadores Micrometer **sem** tags de tenant, produto, reserva ou payload:

| Nome | Quando incrementa |
|---|---|
| `lhamalog.quota.authorized` | reserva `AUTHORIZED` |
| `lhamalog.quota.unlimited` | reserva `AUTHORIZED_UNLIMITED` |
| `lhamalog.quota.insufficient` | saldo insuficiente |
| `lhamalog.quota.no_plan` | sem plano / sem ciclo pago |
| `lhamalog.quota.core_unavailable` | Core fora, timeout ou falha de `confirm` |
| `lhamalog.quota.released` | `release` após sanitização rejeitada/falha |
| `lhamalog.quota.post_debit_write_failure` | falha OpenObserve/stream/DLQ após `confirm` |
| `lhamalog.quota.refund_enqueued` | item criado em `quota_refund_outbox` |
| `lhamalog.quota.refund_succeeded` | Core aceitou `refund` (2xx ou `409` terminal) |
| `lhamalog.quota.refund_retry` | retry transitório do worker de refund |
| `lhamalog.quota.refund_suspended` | `401`/`403` no refund |

### Logs sanitizados (exemplos)

Os logs **não** registram token, corpo OTLP nem saldo. Exemplos de linhas aceitáveis:

```text
Could not release failed reservation quota:<id>
Could not enqueue refund for confirmed reservation quota:<id>
Refund for reservation quota:<id> deferred (HTTP_503)
Destination did not confirm 1 log records of product <productId>: <mensagem operacional>
Quota refund scheduler iteration failed
```

Não copiar para logs o valor de `LHAMALOG_COLLECTOR_CORE_TOKEN` /
`LHAMALOG_CORE_USAGE_TOKEN`.

### Exemplo de cálculo

`0,000010 GB` decimal corresponde a **10 000 bytes**. Reservar 3 000 bytes resulta em
`reservedBytes=3 000`; confirmar resulta em `consumedBytes=3 000` e 7 000 bytes restantes.
Um lote de 8 000 bytes é recusado integralmente com `429` e `Retry-After: 120`, sem escrita.
Ao confirmar e deixar 2 000 bytes ou menos, emite-se uma vez o limiar 20; com 500 bytes ou
menos, uma vez o limiar 5. Refund de lote confirmado de 1 000 bytes após falha de escrita
subtrai 1 000 de `consumedBytes`, sem reemitir alerta. Reserva nunca confirmada expira e
devolve `reservedBytes`. Todos os valores usam bytes e GB decimal (`1e9`).
