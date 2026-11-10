# Modelo de Dados: Volume por Tenant e Produto (LLT-186)

## Fato de uso (`UsageFact`)

Representa um lote aceito em um único domínio, pronto para agregação pelo Core. É imutável depois da criação.

| Campo | Tipo | Regra |
|---|---|---|
| `usageFactId` | string determinística | `SHA-256(tenantId, productId, domain, batchId)`; chave de idempotência ponta a ponta. |
| `batchId` | string | `SHA-256(tenantId, productId, domain, lista ordenada dos recordIds aceitos)`; reenvio lógico mantém o valor. |
| `tenantId` | string | Vem exclusivamente do token validado. |
| `productId` | string | Vem exclusivamente do token validado. |
| `domain` | enum | `LOGS` ou `TRACES`; nunca mistura sinais. |
| `acceptedRecordIds` | lista de strings | IDs determinísticos do conteúdo protobuf de cada registro aceito; sem duplicatas dentro do fato. Não são chave de deduplicação entre lotes. |
| `acceptedRecordCount` | inteiro ≥ 1 | Tamanho de `acceptedRecordIds`; integra a medição integral do lote. |
| `acceptedBytes` | inteiro ≥ 0 | Comprimento da serialização protobuf determinística da requisição reconstruída; integra a medição integral do lote, inclusive envelope. |
| `acceptedAt` | instante UTC | Momento de reconhecimento no Collector. |
| `schemaVersion` | inteiro | Versão do contrato, inicialmente `1`. |

### Regras de agregação no Core

1. O mesmo `usageFactId` é idempotente durante os sete dias de validade da chave: sua repetição não soma contagem nem bytes.
2. Para um fato novo e diferente, o Core soma todos os bytes, inclusive o envelope, e todos os registros aceitos, mesmo que tenham sido medidos em lote anterior.
3. A deduplicação é pelo lote (`batchId`/`usageFactId`), e não por `recordId`; após sete dias, reenvio do mesmo lote pode ser contado novamente.
4. A apresentação em GB é derivada: `bytes / 1.000.000.000` (GB decimal).
5. O Core cria o modelo de ciclo do plano: franquia mensal; ciclo semanal para plano semanal; cobrança mês a mês para plano semestral; início na data obrigatória do primeiro pagamento. A conversão de `acceptedAt` em data civil usa UTC como default técnico documentado.

## Plano/assinatura no Core (`PlanSubscription`)

Nova entidade do Core criada por esta feature para definir o ciclo aplicável ao tenant e produto.

| Campo | Regra |
|---|---|
| `firstPaymentDate` | Obrigatório; define o início do ciclo. Seu preenchimento é dependência externa, pois o Core ainda não possui essa entidade. |

As demais propriedades da entidade e a forma de representar as modalidades de plano não são definidas por
esta documentação.

## Medição canônica (`CanonicalOtlpMeasurement`)

Objeto transitório, não persistido como payload completo.

| Campo | Fonte | Regra |
|---|---|---|
| `signal` | rota/export OTLP | Define `LOGS` ou `TRACES`. |
| `acceptedRequest` | request OTLP reconstruída | Retém apenas resource, scope e registros aceitos. |
| `serializedBytes` | serialização protobuf determinística | Base exclusiva de `acceptedBytes`; corpo lógico descomprimido. |

Não inclui `tenantId`, `productId`, `lhamalog_batch_id`, `lhamalog_record_id`, referência Redis, cabeçalhos,
token ou outros metadados internos adicionados pelo Collector.

## Item de outbox (`UsageOutboxItem`)

Documento MongoDB durável que controla a entrega assíncrona.

| Campo | Tipo | Regra |
|---|---|---|
| `_id` | string | Igual a `usageFactId`; índice único. |
| `fact` | `UsageFact` | Snapshot imutável a ser enviado ao Core. |
| `state` | enum | `PENDING`, `ACCEPTED`, `SYNCED`, `ABANDONED` ou `AMBIGUOUS`. |
| `attemptCount` | inteiro ≥ 0 | Incrementa por tentativa HTTP iniciada após `ACCEPTED`. |
| `nextAttemptAt` | instante UTC | Agenda retry para estado pendente. |
| `leaseOwner` / `leaseUntil` | string / instante | Reserva temporária da sincronização; expiração permite recuperação após reinício. |
| `lastErrorCode` | string opcional | Classe resumida, status HTTP ou causa sanitizada; sem segredo/payload. |
| `createdAt` / `deliveredAt` | instante UTC | Auditoria e operação. |
| `acceptedEvidenceKey` | string | `usage:fact:{usageFactId}`; chave de deduplicação com expiração de sete dias. |

### Transições

```text
criado (majority) → PENDING ── DONE:* ──→ ACCEPTED ── Core 2xx ──→ SYNCED
                       │                     │
       ausente ─ CAS Redis → ABANDONED       └─ rede/timeout/5xx → ACCEPTED (backoff)
                       │
                    CLAIMED:* → AMBIGUOUS (alerta/operação)
```

`PENDING` é candidato e não faturável; bytes, contagens e `recordIds` não podem ser alterados. Upsert com
mesmo `usageFactId` e conteúdo divergente registra erro sem sobrescrever. O reconciliador usa grace só para
liveness e CAS Redis, não tempo como prova. `CLAIMED:*` é ambíguo e exige alerta/resolução operacional.
Se o CAS `ACCEPTED` falhar após `DONE`, o exportador recebe sucesso e o reconciliador recupera. Falhas HTTP
4xx exigem operação/diagnóstico, mas não revertem aceitação.

## Limites e relações

- `UsageFact` nasce como candidato após `StoredBatch` (OpenObserve), antes da publicação Redis/DLQ.
- O script Redis usa `CLAIMED:{token}` e `DONE:{route}:{entryId}` na mesma chave; ambas as rotas são
  evidência aceitável, mas `ABANDONED` bloqueia qualquer `XADD` posterior.
- Um `StoredBatch` aceito gera no máximo um `UsageFact` por domínio/batch; reenvio idêntico encontra o mesmo `_id` enquanto a chave de deduplicação estiver válida por sete dias.
- Uma `UsageOutboxItem` transporta exatamente um `UsageFact`.
- A garantia é condicional à configuração Redis já implantada; esta feature não a altera nem exige Cluster.
  Com AOF `appendfsync everysec`, pode haver perda conjunta de até ~1 s após resposta `200` e consequente
  subcontagem. `appendfsync always` reduz a janela com custo de latência e é recomendação operacional
  pendente, não uma alteração desta feature.
- Plano e ciclo pertencem ao Core e são criados por esta feature; saldo, cotas e reservas pertencem à
  LLT-183. O preenchimento externo de `firstPaymentDate` da nova entidade de plano/assinatura é dependência
  explícita.
