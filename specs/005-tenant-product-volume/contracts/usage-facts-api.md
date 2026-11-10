# Contrato: fatos de uso Collector → Core

> Estado: contrato técnico de implementação. O Core ainda não expõe esta API, autenticação
> serviço-a-serviço, entidade de plano, ciclo ou uso; este arquivo define a integração a implementar nos dois
> repositórios, não um endpoint já disponível.

## Transporte e autenticação

- **Envio**: `POST /internal/usage-facts`.
- **Content-Type**: `application/json`
- **Segurança**: HTTPS obrigatório.
- **Idempotência**: o campo obrigatório `usageFactId` é a chave de idempotência; não usar
  `Idempotency-Key`.
- **Pré-condição de envio**: o Collector envia somente fatos `ACCEPTED`; candidatos `PENDING` e fatos
  `ABANDONED` não são expostos a esta API.
- **Autenticação**: `X-LhamaLog-Service-Token: <token>`. O Collector lê
  `LHAMALOG_COLLECTOR_CORE_TOKEN` e o Core lê `LHAMALOG_CORE_USAGE_TOKEN` por configuração/segredo de
  ambiente. A API não aceita `Authorization`/Clerk. Nenhum valor de segredo é versionado; testes injetam
  valor de teste. Rotação é decisão operacional de infraestrutura pendente.

## Requisição v1

```json
{
  "usageFactId": "sha256-hex-of-tenant-product-domain-and-batch-id",
  "batchId": "sha256-hex-of-tenant-product-domain-and-ordered-record-ids",
  "tenantId": "tenant-123",
  "productId": "product-456",
  "domain": "LOGS",
  "acceptedRecordIds": ["record-a", "record-b"],
  "acceptedRecordCount": 2,
  "acceptedBytes": 1543,
  "acceptedAt": "2026-11-08T20:15:30Z",
  "schemaVersion": 1
}
```

### Validação

| Campo | Regra |
|---|---|
| `usageFactId`, `batchId`, `tenantId`, `productId` | Obrigatórios e não vazios. |
| `domain` | Exatamente `LOGS` ou `TRACES`. |
| `acceptedRecordIds` | Lista não vazia, sem duplicatas; tamanho igual a `acceptedRecordCount`. |
| `acceptedRecordCount` | Inteiro positivo. |
| `acceptedBytes` | Inteiro não negativo; representação decimal em JSON, sem conversão para GB. |
| `acceptedAt` | ISO-8601 UTC; o Core persiste o fato e filtra consultas usando `[from,to)`. |
| `schemaVersion` | `1` neste contrato. |

`usageFactId` deve ser `SHA-256(tenantId, productId, domain, batchId)`, com `batchId =
SHA-256(tenantId, productId, domain, recordIds aceitos ordenados)`. O fato inteiro é deduplicado por essa
chave por sete dias. Somente o reenvio idêntico do mesmo lote, com o mesmo `batchId`/`usageFactId`, não
duplica durante esse prazo. Lote novo e diferente soma integralmente `acceptedRecordCount` e
`acceptedBytes`, inclusive registros já medidos e envelope; não há deduplicação por `recordId`. Após sete
dias, reenvio do mesmo lote pode ser contado novamente.

## Respostas e repetição

| Situação | Resposta | Efeito no Collector |
|---|---|---|
| Fato novo persistido | `201 Created` | Marcar outbox como `SYNCED`. |
| Mesmo `usageFactId` e corpo semanticamente igual | `200 OK` ou `201 Created` | Marcar como `SYNCED`; não duplicar agregação. |
| Mesmo `usageFactId` com corpo diferente | `409 Conflict` | Falha terminal; alertar operador, não repetir automaticamente. |
| Corpo inválido ou versão não aceita | `400` / `422` | Falha terminal; preservar fato e motivo para diagnóstico. |
| Não autenticado/autorizado | `401` / `403` | Suspender retry automático e alertar; manter fato `ACCEPTED` para correção operacional, sem expor credencial em log. |
| Limite temporário ou falha do Core | `429` / `5xx`, timeout ou erro de rede | Reagendar retry; entrega continua *at-least-once*. |

O Core deve tratar `usageFactId` atomicamente com a persistência/agregação do fato. Locks ou leases no
Collector apenas reduzem concorrência e não substituem essa obrigação.

O Core cria o modelo de ciclo do plano e usa `acceptedAt` para a agregação aplicável: franquia mensal, ciclo
semanal para plano semanal, cobrança mês a mês para plano semestral e início na data obrigatória do primeiro
pagamento da nova entidade de plano/assinatura. O preenchimento dessa data é dependência externa, pois a
entidade ainda não existe no Core. UTC é o default técnico documentado para converter `acceptedAt` em data
civil.

## Consultas internas

### Janela de fatos

`GET /internal/usage-facts?tenantId&productId&from&to`

Requer os mesmos cabeçalhos de autenticação. `tenantId`, `productId`, `from` e `to` são obrigatórios; os
instantes são ISO-8601 UTC e a janela é `[from,to)`. Retorna os fatos persistidos para a combinação e
intervalo informados, sem payload OTLP, token ou metadados internos.

### Agregado do ciclo vigente

`GET /internal/usage-facts/current-cycle?tenantId&productId`

Requer os mesmos cabeçalhos. Retorna a agregação de contagem e bytes do ciclo vigente calculado conforme
[RF-006 e RF-014](../spec.md#requisitos-funcionais), incluindo os limites do ciclo e a apresentação derivada em GB decimal. O ciclo usa
`firstPaymentDate` obrigatório e UTC como default técnico para a data civil.

## Fora do contrato

- consultar ou reservar cota, saldo ou token de reserva (LLT-183);
- enviar payload OTLP, token de produto, dados de log/span ou metadados internos do Collector;
- prometer entrega exatamente uma vez.
