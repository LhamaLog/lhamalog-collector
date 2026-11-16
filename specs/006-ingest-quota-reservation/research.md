# Pesquisa e Decisões de Design — Reserva de Cota

## 1. Reuso da medição da spec 005

**Decisão**: reutilizar a identidade de lote, os bytes OTLP canônicos, os domínios `LOGS` e
`TRACES`, o `UsageFact` e a outbox já entregues pela spec 005; esta feature reserva os bytes
canônicos e logs/traces compartilham o saldo por tenant, produto e ciclo.

**Justificativa**: o Collector já forma fatos por tenant, produto, domínio e lote aceito, e o
Core já agrega o ciclo por domínio. Uma segunda contagem poderia divergir de bytes, reenvio
idêntico e `ABANDONED`.

**Alternativas consideradas**:
- Recalcular saldo a partir de tamanho do transporte: rejeitada, pois JSON, protobuf, HTTP,
  gRPC e compressão não são a medida canônica aprovada.
- Criar contador independente apenas para reserva: rejeitada; bytes canônicos da spec 005 são
  a unidade de saldo aprovada.

## 2. Marco de reserva e confirmação

**Decisão**: o Collector inicia a reserva em paralelo à sanitização/ocultação. Ao concluir a
sanitização, confirma a reserva de forma síncrona e somente depois escreve no OpenObserve ou
publica no stream/DLQ. A ausência de confirmação expira em 20 minutos.

**Justificativa**: estas são decisões de produto já aprovadas e impedem a fila de receber lote
sem crédito, sem reter saldo permanentemente após falha.

**Alternativas consideradas**:
- Reservar depois de publicar: rejeitada, pois permitiria encaminhamento acima da franquia.
- Confirmar por módulo externo após a publicação: rejeitada, pois permitiria persistir ou
  publicar lote sem débito confirmado.

## 3. Ciclo pago e data do primeiro pagamento

**Decisão**: Core é a fonte de verdade para plano, ciclo e `firstPaymentDate`; na data civil
UTC anterior a essa data, não há ciclo pago e a ingestão é recusada.

**Justificativa**: `UsageCycle` já calcula ciclo a partir de `firstPaymentDate`, e a nova
política de recusa deve usar a mesma regra temporal.

**Alternativas consideradas**:
- Permitir ciclo futuro: rejeitada pela decisão de produto de 2026-11-10.
- Decidir no Collector: rejeitada, pois duplicaria dados contratuais e regras do Core.

## 4. Idempotência e estados de reserva

**Decisão**: as operações de reservar, confirmar e liberar precisam de chave idempotente e
transições terminais mutuamente exclusivas (`CONFIRMED`, `RELEASED`, `EXPIRED`).

**Justificativa**: transportes e workers podem repetir chamadas; sem idempotência, retries
podem consumir ou devolver saldo mais de uma vez.

**Alternativas consideradas**:
- Operações apenas best effort: rejeitada, pois não oferece correção em reinício/falha de rede.
- Usar somente a idempotência de usage fact: insuficiente, porque a reserva precede o fato e
  pode ser liberada sem que o fato exista.

## 5. Contrato, autenticação e dados sensíveis

**Decisão**: o contrato é HTTP interno, usa `X-LhamaLog-Service-Token` normalizado, sem
`Authorization`, e não registra valores de token. As rotas, campos e respostas normativos
estão no contrato de `reservatokens`.

**Justificativa**: o Core já protege usage facts com token de serviço. Segredos são proibidos
pela constituição.

**Alternativas consideradas**:
- Token de produto no contrato interno: rejeitada como padrão, pois token de produto não é o
  canal de serviço já existente.
- Novo token: rejeitado; o contrato reutiliza o token de serviço existente.

## 6. Alerta obrigatório de saldo baixo

**Decisão**: depois de cada `confirm` que efetiva débito, o Core calcula o saldo restante e
emite `SALDO_BAIXO` ao cruzar 20% e 5% da franquia do ciclo. O evento tem gravidade média,
papéis obrigatórios `DONO` e `ADMINISTRADOR`, canais `WEB` e `EMAIL`, e chave única
`tenantId+productId+cycleStart+threshold`.

**Justificativa**: os limites e os destinatários foram definidos pelo Michel em 2026-11-11;
a checagem no `confirm` evita alerta por crédito somente reservado e posteriormente liberado.

**Decisão de infraestrutura**: o Core já declara Redis, mas não possui publicador de stream
de notificação. Criar porta de publicação e Mongo outbox para entrega confiável ao stream;
o adaptador Redis Streams é a implementação planejada. Matriz ADR_134/136/130/165 e entrega
WEB/E-MAIL pertencem ao módulo Notificação, fora destes repositórios.

## 7. Reembolso pós-débito

**Decisão**: se OpenObserve, stream ou DLQ falhar depois de `confirm`, o Collector persiste
um pedido de refund idempotente em outbox Mongo, único por `reservationId`. O worker repete
com backoff limitado e datas BSON-safe; `401`/`403` vão para `SUSPENDED` e
`409 RESERVATION_NOT_REFUNDABLE` é terminal (`DONE`, com código e log sanitizado).

**Justificativa**: o débito precede a escrita/publicação; sem compensação durável, uma falha
posterior cobraria dados não aceitos.

**Alternativas consideradas**:
- `release` após `CONFIRMED`: rejeitada; permanece `409`, pois somente `refund` pode reverter
  débito confirmado.
- Reemitir `SALDO_BAIXO` após refund: rejeitada; cada limiar permanece emitido uma vez por ciclo.
