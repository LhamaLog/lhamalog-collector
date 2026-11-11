# Especificação da Feature: Reserva de Cota para Ingestão (LLT-186)

**Feature Branch**: `feature/LLT-186-tenant-product-volume`  
**Criado em**: 2026-11-10  
**Status**: Pronta para implementação  
**Entrada**: LLT-186 — recusar a ingestão antes da fila quando não houver saldo ou ciclo pago aplicável, preservando a medição de volume já definida na spec 005.

## Clarifications

### Sessão 2026-11-11

- Q: Quando reservar, confirmar e publicar um lote? → A: Reservar em paralelo à sanitização, confirmar antes de OpenObserve/stream/DLQ.
- Q: Como avisar saldo baixo? → A: Emitir `SALDO_BAIXO` uma vez em 20% e 5%, com gravidade média, `DONO`/`ADMINISTRADOR` e `WEB`/`EMAIL`.
- Q: Como tratar falha de escrita ou publicação depois de `confirm`? → A: Enfileirar no outbox Mongo durável do Collector um reembolso idempotente ao Core; repetir com backoff, teto, datas BSON-safe e suspender em `401`/`403`.
- Q: Quais bytes são reservados e debitados? → A: Os bytes canônicos do lote elegível como recebido, antes da ocultação/sanitização, exatamente iguais aos bytes reservados.

## Cenários de Usuário e Testes *(obrigatório)*

### História de Usuário 1 - Autorizar ingestão com saldo reservável (Prioridade: P1)

Como cliente com plano e saldo disponível, quero que um lote elegível tenha saldo reservado antes de seguir para o processamento, para que a ingestão não ultrapasse a franquia contratada.

**Por que esta prioridade**: É o fluxo que permite ingerir dados dentro da cota sem alterar a medição já existente.

**Teste independente**: Enviar lotes de logs e traces para um tenant com ciclo pago e saldo suficiente; verificar reserva em paralelo à sanitização, confirmação pelo Collector antes de OpenObserve/stream e correlação com o fato de uso.

**Cenários de aceitação**:

1. **Dado** um tenant com plano, ciclo pago aplicável e saldo suficiente, **quando** enviar um lote válido, **então** o lote deverá ser autorizado por uma reserva antes de seguir para a fila.
2. **Dado** uma reserva autorizada e sanitização concluída, **quando** o Collector confirmar a reserva, **então** o consumo será efetivado uma única vez antes de gravar no OpenObserve ou publicar no stream/DLQ.
3. **Dado** uma reserva pendente de confirmação, **quando** transcorrerem 20 minutos, **então** o saldo reservado deverá ser estornado.
4. **Dado** uma reserva confirmada, **quando** OpenObserve, stream ou DLQ falhar depois do débito, **então** o Collector deverá enfileirar um reembolso idempotente e a resposta OTLP continuará indisponível.

---

### História de Usuário 2 - Recusar ingestão sem saldo ou ciclo pago (Prioridade: P1)

Como cliente sem saldo disponível ou sem ciclo pago aplicável, quero receber uma recusa recuperável antes da ingestão ser enfileirada, para que não sejam aceitos dados sem crédito.

**Por que esta prioridade**: A decisão de produto de 2026-11-10 exige impedir consumo acima do saldo e antes do primeiro ciclo pago.

**Teste independente**: Enviar lotes para tenants sem saldo, sem ciclo pago e cuja data civil UTC seja anterior a `firstPaymentDate`; verificar que o lote não é enviado à fila, não gera consumo de crédito e recebe a recusa correspondente ao protocolo.

**Cenários de aceitação**:

1. **Dado** um tenant cujo saldo seja insuficiente, **quando** enviar um lote, **então** o lote inteiro será recusado, removido da área temporária e não seguirá para a fila.
2. **Dado** um tenant sem ciclo pago, inclusive em data civil UTC anterior a `firstPaymentDate`, **quando** enviar um lote, **então** a ingestão será recusada e nenhum crédito será consumido.
3. **Dado** uma recusa por saldo ou ciclo, **quando** o saldo ainda não tiver sido atualizado, **então** uma nova tentativa não poderá consumir crédito.

---

### História de Usuário 3 - Liberar reserva não efetivada (Prioridade: P2)

Como operador, quero que reservas não confirmadas sejam liberadas em caso de falha antes do débito ou expiração, para que o saldo não fique retido indevidamente.

**Por que esta prioridade**: Falhas após a autorização não podem reduzir permanentemente o saldo do tenant.

**Teste independente**: Simular rejeição na sanitização após reserva e expiração sem confirmação; verificar liberação explícita ou estorno automático ao fim de 20 minutos.

**Cenários de aceitação**:

1. **Dado** que a reserva foi obtida, **quando** a sanitização rejeitar ou falhar antes da confirmação, **então** o Collector deverá solicitar a liberação da reserva.
2. **Dado** que a confirmação não foi recebida, **quando** a reserva expirar, **então** ela deverá ser estornada sem consumo efetivo.
3. **Dado** que o Core esteja indisponível durante a decisão de reserva, **quando** um lote chegar, **então** a ingestão será recusada temporariamente em falha fechada, sem cache de saldo ou limite.

### Casos de Borda

- Lote vazio ou totalmente inválido não cria reserva nem consumo.
- Logs e traces compartilham o mesmo saldo por tenant, produto e ciclo; ambos reservam bytes canônicos do lote elegível.
- Reenvio de lote `ABANDONED` continua sujeito à regra já aprovada na spec 005: pode ser reenviado e medido uma vez.
- Lote recusado não é publicado no stream/DLQ, não alcança OpenObserve e não gera fato de uso aceito.
- Uma reserva confirmada não pode ser liberada; operações repetidas de reservar, confirmar ou liberar devem ter comportamento idempotente definido no contrato.
- Reserva confirmada que falhar posteriormente na escrita/publicação é reembolsada por outbox; replay de `refund` não devolve crédito duas vezes.
- Ao reenviar um lote cuja reserva anterior está `REFUNDED`, o `reserve` cria nova reserva com o mesmo `reservationId`, como já ocorre para `RELEASED` ou `EXPIRED`.
- Falha de confirmação por indisponibilidade, timeout, 5xx, `EXPIRED` ou `RELEASED` corta a execução antes de OpenObserve/stream/DLQ e retorna indisponibilidade.

## Requisitos *(obrigatório)*

### Requisitos Funcionais

- **RF-001**: O sistema DEVE consultar a elegibilidade de saldo do tenant identificado pelo token antes de gravar o lote no OpenObserve ou publicá-lo no stream.
- **RF-002**: O sistema DEVE manter o lote em área temporária enquanto aguarda o resultado da reserva, sem encaminhá-lo à fila antes da autorização.
- **RF-003**: Para tenant com saldo suficiente ou com `unlimitedUsage=true`, o sistema DEVE permitir que o lote autorizado siga para o fluxo de fila existente; logs e traces usam o mesmo saldo por tenant, produto e ciclo.
- **RF-004**: Para tenant sem saldo, o sistema DEVE recusar o lote, removê-lo da área temporária e não criar consumo efetivo.
- **RF-005**: Para tenant sem plano ou sem ciclo pago aplicável, inclusive antes de `firstPaymentDate` na data civil UTC, o sistema DEVE recusar o lote e não criar consumo efetivo.
- **RF-006**: O sistema DEVE expirar e estornar automaticamente uma reserva não confirmada após 20 minutos.
- **RF-007**: Se o lote não puder ser salvo ou encaminhado antes de `confirm`, o Collector DEVE solicitar a liberação da reserva.
- **RF-008**: O Collector DEVE solicitar reserva em paralelo à sanitização/ocultação do lote; ao terminar a sanitização, DEVE confirmar a reserva antes de OpenObserve e stream/DLQ. O `reservationId` pode acompanhar o evento somente para rastreabilidade.
- **RF-009**: A contagem de uso aceita, seus domínios, a identidade de lote e a idempotência já definidos na spec 005 DEVEM ser preservados; esta feature não cria uma segunda forma de medir bytes.
- **RF-010**: A resposta ao exporter DEVE expressar a recusa para OTLP HTTP protobuf e JSON com HTTP `429` e `Retry-After: 120` sem saldo, ou HTTP `503` e `Retry-After: 120` sem plano ou Core indisponível; em gRPC DEVE usar respectivamente `RESOURCE_EXHAUSTED` e `UNAVAILABLE` com metadado equivalente.
- **RF-011**: A autenticação entre Collector e Core DEVE usar exclusivamente o cabeçalho `X-LhamaLog-Service-Token`, normalizado e sem `Authorization`, sem registrar valores de segredo.
- **RF-012**: Na indisponibilidade, timeout ou resposta 5xx do Core durante a reserva, o Collector DEVE falhar fechado, descartar o lote temporário, não publicar nem assumir reserva sem sucesso e responder como indisponibilidade.
- **RF-013**: O Core DEVE manter o saldo por `tenantId`, `productId` e ciclo em bytes, com `allowanceBytes`, `reservedBytes` e `consumedBytes`, e reservar atomica e condicionalmente para impedir sobreconsumo concorrente.
- **RF-014**: O Core DEVE emitir uma vez por ciclo os alertas `SALDO_BAIXO` ao cruzar 20% e 5% do saldo restante, com gravidade média, papéis obrigatórios `DONO` e `ADMINISTRADOR` e canais obrigatórios `WEB` e `EMAIL`.
- **RF-015**: A emissão de `SALDO_BAIXO` DEVE ser idempotente pela chave `tenantId+productId+cycleStart+threshold`; a resolução da matriz e a entrega WEB/E-MAIL pertencem ao módulo Notificação e estão fora destes repositórios.
- **RF-016**: Confirmação de reserva `EXPIRED` ou `RELEASED` DEVE devolver `409 RESERVATION_NOT_ACTIVE`, sem débito; o Collector DEVE tratá-la como indisponibilidade e não gravar nem publicar o lote.
- **RF-017**: Depois de `confirm`, se a escrita no OpenObserve ou a publicação no stream/DLQ falhar, o Collector DEVE enfileirar um pedido de reembolso idempotente em outbox Mongo durável, com retry/backoff limitado, datas BSON-safe e estado `SUSPENDED` para `401`/`403`.
- **RF-018**: O Core DEVE executar `POST /internal/usage-reservations/{id}/refund` atomicamente: `CONFIRMED` passa a `REFUNDED` e reduz `consumedBytes` exatamente uma vez; replay de `REFUNDED` retorna `204`; `RESERVED`, `RELEASED` e `EXPIRED` retornam `409 RESERVATION_NOT_REFUNDABLE`.
- **RF-019**: O reembolso de reserva `AUTHORIZED_UNLIMITED` DEVE retornar `204` sem mutar saldo; reembolso não DEVE reemitir chaves `SALDO_BAIXO` já emitidas no ciclo.
- **RF-020**: O débito em `confirm` DEVE usar os bytes canônicos do lote elegível recebido antes da sanitização/ocultação, exatamente iguais aos bytes originalmente reservados.

### Entidades-Chave

- **Lote temporário**: Lote validado que aguarda a decisão de reserva e ainda não entrou no fluxo de fila.
- **Reserva de cota**: Autorização temporária, identificada de forma idempotente, que retém saldo por até 20 minutos.
- **Saldo de ciclo**: Crédito disponível em bytes no ciclo pago aplicável ao tenant e produto, compartilhado entre logs e traces.
- **Efetivação**: Marco em que o Collector confirma a reserva depois da sanitização e antes de OpenObserve/stream/DLQ, produzindo o consumo efetivo.
- **Reembolso de reserva**: Compensação idempotente para falha posterior ao débito, solicitada pelo outbox durável do Collector.
- **Fato de uso**: Registro de medição já definido pela spec 005, que deve manter relação rastreável com a reserva quando aplicável.

## Decisões registradas

### Decisões do Michel

1. Saldo em bytes; o plano é contratado e exibido em GB decimal (`1 GB = 1_000_000_000 bytes`), como na spec 005.
2. Logs e traces usam um saldo único por tenant, produto e ciclo.
3. Lote que excede o saldo restante é recusado integralmente.
4. `Retry-After` fixo de 120 segundos.
5. Core indisponível usa falha fechada, sem cache.
6. `SALDO_BAIXO` é emitido uma vez por limiar de 20% e 5% do saldo remanescente por ciclo, com gravidade média; os destinatários obrigatórios são `DONO` e `ADMINISTRADOR`, pelos canais `WEB` e `EMAIL`, além da matriz de notificações.
7. Sem saldo retorna HTTP `429`/`Retry-After` e gRPC `RESOURCE_EXHAUSTED`; sem plano retorna HTTP `503`/`Retry-After` e gRPC `UNAVAILABLE`.
8. O Collector reserva em paralelo à sanitização/ocultação; após sanitizar, confirma antes de escrever no OpenObserve ou publicar no stream/DLQ. Sem autorização, plano ou Core disponível, corta a execução.
9. O TTL de 20 minutos é apenas a proteção para reserva nunca confirmada, como queda do Collector entre reserva e confirmação. Reenvio de confirmação não debita duas vezes.
10. Falha depois de `confirm` em OpenObserve, stream ou DLQ solicita reembolso idempotente por outbox Mongo durável; o protocolo OTLP preserva `503`/`UNAVAILABLE` e `Retry-After: 120`.
11. O débito e a reserva usam bytes canônicos do lote elegível recebido antes da sanitização/ocultação; os dois valores são idênticos.

### Decisões técnicas do dev

- A API interna usa `X-LhamaLog-Service-Token` normalizado, sem `Authorization`, nas rotas idempotentes `POST /internal/usage-reservations`, `POST /internal/usage-reservations/{reservationId}/confirm` e `POST /internal/usage-reservations/{reservationId}/release`.
- A reserva recebe `reservationId` estável por lote, `tenantId`, `productId`, `requestedBytes` canônico, `batchId` opcional e `domain`. Retorna `AUTHORIZED`, `AUTHORIZED_UNLIMITED`, `INSUFFICIENT_BALANCE` ou `NO_PLAN`.
- Sem plano significa não haver `PlanSubscription` aplicável ou ciclo pago, inclusive antes de `firstPaymentDate`; sem limite exige plano, ciclo pago e `unlimitedUsage=true`.
- `reserve` soma bytes em `reservedBytes`; `confirm` os move para `consumedBytes`; `release` e expiração os devolvem. `refund` move `CONFIRMED` para `REFUNDED` por CAS e subtrai exatamente uma vez de `consumedBytes`. O alerta `SALDO_BAIXO` é avaliado após o débito no `confirm`, usando atualização condicional e chave única Mongo, e não é removido nem reemitido pelo reembolso.
- O Core já possui cliente Redis, mas não há publicador de notificação existente: esta entrega prevê uma porta de publicação e Mongo outbox para o stream de notificação. A resolução da matriz ADR_134/136/130/165 e a entrega WEB/E-MAIL são externas ao módulo Notificação.

## Histórico de decisão

> **Substituído em 11/11/2026 por `RF-017`–`RF-020`:** o reembolso pós-débito deixou de ser sugestão ou bloqueio; é comportamento obrigatório.

## Histórico de bytes canônicos

> **Decidido em 11/11/2026:** os bytes reservados e debitados são os bytes canônicos do lote elegível **recebido antes da sanitização/ocultação**, pois reserva e sanitização ocorrem em paralelo.

## Critérios de Sucesso *(obrigatório)*

### Resultados Mensuráveis

- **CS-001**: Em 100% dos cenários de teste com saldo suficiente ou tenant sem limite, o lote somente alcança a fila após uma reserva válida.
- **CS-002**: Em 100% dos cenários de saldo insuficiente, ausência de ciclo pago e data UTC anterior a `firstPaymentDate`, nenhum lote recusado alcança a fila ou consome crédito.
- **CS-003**: Em 100% dos cenários de falha antes da confirmação e dos cenários de expiração, o saldo reservado é liberado explicitamente ou estornado em até 20 minutos.
- **CS-004**: Em 100% dos cenários de confirmação, cada reserva efetivada pode ser correlacionada ao lote no stream e ao fato de uso correspondente, sem alterar a idempotência de medição da spec 005.
- **CS-005**: Os testes de contrato demonstram HTTP `429`/`503` e gRPC `RESOURCE_EXHAUSTED`/`UNAVAILABLE`, com retry de 120 segundos, para as três modalidades OTLP suportadas.
- **CS-006**: Os testes demonstram uma emissão `SALDO_BAIXO` para 20% e uma para 5% por tenant, produto e ciclo, sem repetição em replays.
- **CS-007**: Em 100% dos cenários de falha pós-débito avaliados, o pedido de reembolso é persistido duravelmente, reduz `consumedBytes` no máximo uma vez e preserva as emissões `SALDO_BAIXO` já realizadas.

## Premissas

- Limites por plano e conta pertencem ao Core e são persistidos com o ciclo/assinatura do tenant.
- O tenant e o produto usados na reserva são derivados do token do produto, como na medição existente.
- O TTL de uma reserva não confirmada é de 20 minutos.
- Enquanto o saldo não for atualizado, o sistema não consome crédito.
- A medição entregue pela spec 005, incluindo fatos de uso, ciclo, token de serviço e reenvio de `ABANDONED`, é uma dependência existente e não será redesenhada.
