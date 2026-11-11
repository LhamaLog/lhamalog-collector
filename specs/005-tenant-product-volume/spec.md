# Especificação da Feature: Volume por Tenant e Produto

**Feature Branch**: `feature/LLT-186-tenant-product-volume`  
**Criado em**: 2026-11-08  
**Status**: Implementação em andamento no worktree local; revisões dos PRs aplicadas, ainda sem release
**Responsável**: michelbocchijr@gmail.com  
Documentação da medição: [docs/usage-measurement.md](../../docs/usage-measurement.md).

**Entrada**: LLT-186 — medir o volume ingerido por tenant e produto no Collector para que o Core agregue o uso no ciclo do plano.

## Cenários de Usuário e Testes *(obrigatório)*

### História de Usuário 1 - Reconhecer o volume aceito por domínio (Prioridade: P1)

Como responsável pelo produto, quero que o Collector reconheça a contagem e o volume de registros válidos aceitos para cada combinação de tenant, produto e domínio (logs ou traces), para que o uso ingerido seja mensurável com precisão.

**Por que esta prioridade**: Sem uma medição confiável do que foi aceito, o uso do cliente não pode ser agregado nem relacionado ao seu plano.

**Teste independente**: Enviar registros válidos de logs e traces para mais de uma combinação de tenant e produto, incluindo um lote misto, e verificar que os totais reconhecidos distinguem cada combinação e domínio e excluem os registros inválidos.

**Cenários de aceitação**:

1. **Dado** um lote com registros válidos de logs para um tenant e produto, **quando** todos os registros forem aceitos, **então** a contagem e os bytes reconhecidos deverão incluir todos os registros do lote somente no domínio logs.
2. **Dado** um lote com registros válidos de traces para um tenant e produto, **quando** todos os registros forem aceitos, **então** a contagem e os bytes reconhecidos deverão incluir todos os registros do lote somente no domínio traces.
3. **Dado** um lote misto com registros válidos e inválidos, **quando** o Collector concluir a validação, **então** somente o subconjunto válido poderá contribuir para a contagem e os bytes reconhecidos.
4. **Dado** registros válidos de tenants ou produtos distintos, **quando** forem reconhecidos, **então** seus totais deverão permanecer separados por tenant, produto e domínio.

---

### História de Usuário 2 - Reconhecer somente após o encaminhamento confirmado (Prioridade: P1)

Como responsável pelo produto, quero que o volume seja reconhecido somente após a gravação no OpenObserve e o encaminhamento confirmado para o stream de processamento ou para a DLQ, para que a medição represente registros efetivamente aceitos pelo fluxo do Collector.

**Por que esta prioridade**: Reconhecer antes desse ponto pode registrar uso de dados que não passaram pelas etapas exigidas do fluxo.

**Teste independente**: Processar registros válidos em cenários que terminem no stream e na DLQ, bem como um cenário sem a confirmação necessária, e verificar em quais casos há reconhecimento de volume.

**Cenários de aceitação**:

1. **Dado** um registro válido gravado no OpenObserve e publicado com confirmação no stream, **quando** o fluxo for concluído, **então** o registro poderá ser reconhecido para medição.
2. **Dado** um registro válido gravado no OpenObserve e publicado com confirmação na DLQ, **quando** o fluxo for concluído, **então** o registro poderá ser reconhecido para medição.
3. **Dado** uma resposta HTTP 200 da DLQ sem evidência de processamento final, **quando** ela for recebida, **então** ela não deverá, isoladamente, caracterizar o processamento final do registro.
4. **Dado** um registro válido sem confirmação de gravação no OpenObserve ou sem confirmação de publicação no stream ou na DLQ, **quando** o fluxo for interrompido, **então** o registro não poderá ser reconhecido como volume aceito.

---

### História de Usuário 3 - Agregar o uso no ciclo do plano sem cobrança duplicada (Prioridade: P2)

Como responsável pelo Core, quero receber um contador confiável de volume aceito do Collector para agregar o uso por tenant e produto no ciclo do plano, sem cobrar novamente o mesmo registro reenviado.

**Por que esta prioridade**: A medição só gera valor de negócio quando pode compor o uso do ciclo do plano sem superestimar a cobrança.

**Teste independente**: Reenviar o mesmo lote já aceito dentro de sete dias e enviar um lote novo e diferente que contenha um registro já medido; verificar que o reenvio idêntico não altera contagem nem bytes, enquanto o lote novo conta integralmente — inclusive registros já medidos e envelope.

**Cenários de aceitação**:

1. **Dado** contadores confiáveis de logs e traces de um tenant e produto, **quando** o Core os agregar no ciclo aplicável do plano, **então** o uso do ciclo deverá preservar a separação por produto e domínio.
2. **Dado** o mesmo lote anteriormente aceito que seja reenviado, **quando** a medição for agregada, **então** seu `batchId` determinístico deverá impedir que ele aumente a cobrança mais de uma vez.
3. **Dado** um registro já medido que venha em lote novo e diferente, **quando** a medição for agregada, **então** a contagem e os bytes de todo o lote, inclusive o envelope, deverão ser considerados novamente.
4. **Dado** o volume em bytes de registros aceitos, **quando** ele for apresentado ou utilizado para o plano, **então** a conversão para GB deverá usar base decimal.
5. **Dado** uma nova entidade de plano/assinatura com data do primeiro pagamento, **quando** o Core agregar fatos aceitos, **então** deverá aplicar franquia mensal, ciclo semanal para plano semanal, cobrança mês a mês para plano semestral e converter `acceptedAt` em data civil usando UTC.

### Casos de Borda

- Lotes vazios, totalmente inválidos ou com apenas registros de um domínio não devem gerar volume em outro domínio.
- Falha antes de qualquer confirmação necessária não deve antecipar o reconhecimento do volume.
- O reenvio idêntico do mesmo lote aceito, identificado pelo mesmo `batchId`/`usageFactId`, não deve gerar nova contagem nem novos bytes enquanto a chave de deduplicação estiver válida.
- Um lote novo e diferente conta integralmente, inclusive registros já medidos e envelope; um mesmo registro pode ser cobrado mais de uma vez quando vier em lotes diferentes.
- O recebimento de HTTP 200 da DLQ sem processamento final não encerra, por si só, o tratamento do registro.
- Após a aceitação, indisponibilidade do Core não deve impedir a confirmação ao exportador: o fato de uso deve ser persistido de forma durável e sincronizado posteriormente com retry e idempotência no Core.

## Requisitos *(obrigatório)*

### Requisitos Funcionais

- **RF-001**: O sistema DEVE medir, para cada tenant, produto e domínio, a contagem de registros válidos aceitos e seus bytes.
- **RF-002**: O sistema DEVE manter os totais de logs separados dos totais de traces.
- **RF-003**: Em lote misto, o sistema DEVE contar somente os registros válidos aceitos; registros inválidos não podem contribuir para contagem nem bytes.
- **RF-004**: Um registro válido só DEVE ser reconhecido para medição após confirmação de gravação no OpenObserve e confirmação de publicação no stream de processamento ou na DLQ.
- **RF-005**: Uma resposta HTTP 200 da DLQ, isoladamente, NÃO DEVE ser tratada como confirmação de processamento final.
- **RF-006**: O Core DEVE criar e manter o modelo de ciclo do plano e agregar os contadores confiáveis recebidos do Collector no ciclo aplicável por tenant e produto. A franquia é mensal; plano semanal usa ciclo semanal; plano semestral é cobrado mês a mês; e o ciclo começa na data do primeiro pagamento.
- **RF-007**: A conversão de bytes para GB DEVE usar base decimal, conforme o Plano de Monetização.
- **RF-008**: O sistema DEVE impedir cobrança duplicada somente para reenvio idêntico do mesmo lote usando identificação determinística: o mesmo lote aceito deve produzir o mesmo `batchId`/`usageFactId` e ser contabilizado uma única vez enquanto a chave de deduplicação estiver válida. A deduplicação de contagem e bytes DEVE ser pelo lote (`batchId`/`usageFactId`), e NÃO por `recordId`. Um lote novo e diferente DEVE contar integralmente, inclusive registros já medidos e envelope; portanto, um mesmo registro PODE ser cobrado mais de uma vez se vier em lotes diferentes.
- **RF-009**: Os bytes medidos DEVEM ser o tamanho da representação OTLP canônica em protobuf serializado e descomprimido do subconjunto aceito, independentemente de o recebimento ter sido em JSON ou protobuf, HTTP ou gRPC. A medida DEVE incluir `resource`, `scope` e o envelope do lote aceito; metadados internos acrescentados pelo Collector NÃO DEVEM ser faturados. O envelope é contado por lote aceito, portanto reagrupar lotes pode alterar seus bytes de overhead.
- **RF-010**: Após a aceitação, o Collector DEVE confirmar a ingestão ao exportador mesmo se o Core estiver indisponível. O fato de uso DEVE ser persistido de forma durável e sincronizado posteriormente com retry; o Core DEVE tratar a sincronização com idempotência. A entrega de rede é ao menos uma vez (*at-least-once*) e esta feature NÃO promete exatamente uma vez.
- **RF-011**: Esta feature NÃO DEVE criar, reservar, expirar nem liberar cotas ou reservas de uso; esses comportamentos pertencem à LLT-183.
- **RF-012**: A chave de deduplicação DEVE expirar após sete dias. Depois desse prazo, o reenvio do mesmo lote PODE ser contado novamente. A feature NÃO DEVE assumir 40 dias nem retenção indefinida.
- **RF-013**: A API interna de medição Core DEVE aceitar autenticação do Collector exclusivamente por token de serviço dedicado, provisionado por configuração/segredo de ambiente. A API NÃO DEVE aceitar token Clerk. O Collector envia `X-LhamaLog-Service-Token: <token>` nas rotas internas `POST /internal/usage-facts`, `GET /internal/usage-facts?tenantId&productId&from&to` e `GET /internal/usage-facts/current-cycle?tenantId&productId`; nenhum valor de token pode constar em código, teste ou documentação versionada.
- **RF-014**: O Core cria o modelo de ciclo do plano: a franquia é mensal, plano semanal tem ciclo semanal, plano semestral é cobrado mês a mês, e o ciclo começa na data do primeiro pagamento. A data do primeiro pagamento é campo obrigatório da nova entidade de plano/assinatura criada no Core; como o Core ainda não possui essa entidade, seu preenchimento é dependência externa. Para converter `acceptedAt` em data civil, o default técnico documentado é UTC. Lotes novos e diferentes contam integralmente, sem deduplicação por `recordId`.

### Entidades-Chave

- **Registro aceito**: Registro de log ou trace que é válido e atingiu o ponto de reconhecimento definido nesta especificação.
- **Medição de volume**: Total de contagem e bytes de registros aceitos, identificado por tenant, produto e domínio.
- **Ciclo do plano**: Período em que o Core agrega as medições de um tenant e produto para compor o uso do plano.
- **Entrega de contador**: Sincronização do fato de uso persistido pelo Collector ao Core, feita com retry e idempotência no Core após a aceitação.

## Clarifications

### Decidido por Michel

- A medição abrange contagem de registros aceitos e bytes, com domínios separados para logs e traces.
- Apenas registros válidos aceitos são medidos; em lote misto, conta-se somente o subconjunto válido.
- O reconhecimento ocorre após confirmação de gravação no OpenObserve e publicação no stream ou na DLQ; HTTP 200 da DLQ não significa processamento final.
- A agregação por ciclo do plano é responsabilidade do Core e está incluída no escopo; o Collector deve entregar um contador confiável.
- GB usa base decimal, conforme o Plano de Monetização.
- Cotas e reservas da LLT-183 — incluindo reservation tokens, TTL de 20 minutos e liberação — estão fora do escopo e não devem ser misturadas a esta feature.

### Sessão 2026-11-08 — Decidido por Michel

- Q: Qual representação deve definir os bytes aceitos? → A: A representação OTLP canônica em protobuf serializado e descomprimido do subconjunto aceito, incluindo resource, scope e envelope; metadados internos do Collector não são faturados.
- Q: Como reenvios devem ser deduplicados? → A: Substituída pela sessão 2026-11-09: a deduplicação é pelo lote (`batchId`/`usageFactId`), não por `recordId`.
- Q: Como tratar indisponibilidade do Core após a aceitação? → A: Confirmar ingestão ao exportador, persistir duravelmente o fato de uso e sincronizar depois com retry e idempotência no Core; a rede é ao menos uma vez, sem promessa de exatamente uma vez.
- Q: Qual retenção vale para a deduplicação? → A: Substituída pela sessão 2026-11-09: a chave de deduplicação expira após sete dias.
- Q: Como o Collector autentica na API interna de medição do Core? → A: Token de serviço dedicado, provisionado por campo protegido; a API não aceita token Clerk. Cabeçalho, rota, transporte e rotação são escolhas técnicas a definir no contrato.
- Q: Qual janela/ciclo o Core usa e como trata lotes parcialmente sobrepostos? → A: Substituída pela sessão 2026-11-09: o Core cria o modelo de ciclo nas regras definidas, e lote novo e diferente conta integralmente.

As decisões acima foram atribuídas a Michel e retransmitidas por BimBot Escola TI.

### Sessão 2026-11-09 — Decidido por Michel (BimBot Escola TI)

- Q: Como o Core deve criar e aplicar o ciclo do plano? → A: A LLT-186 cria o modelo de ciclo no Core; a franquia é mensal, plano semanal tem ciclo semanal, plano semestral é cobrado mês a mês e o ciclo começa na data do primeiro pagamento.
- Q: Como um lote novo e diferente com registros já medidos deve ser contabilizado? → A: O lote inteiro, inclusive registros já medidos e envelope, conta novamente; somente o reenvio idêntico com o mesmo `batchId`/`usageFactId` não duplica, e não há deduplicação por `recordId`.
- Q: Por quanto tempo a chave de deduplicação deve valer? → A: Sete dias; depois disso, o reenvio do mesmo lote pode ser contado novamente.
- Q: Qual autenticação a API interna de medição aceita? → A: Exclusivamente token de serviço dedicado, provisionado por campo protegido; token Clerk não é aceito.

## Riscos Não Resolvidos

- O significado operacional de “confirmação de publicação” para stream e DLQ precisa ser refletido no contrato futuro, sem supor garantias de atomicidade.
- A criação da nova entidade de plano/assinatura no Core e o preenchimento externo obrigatório de sua data do primeiro pagamento são dependências para aplicar os ciclos. UTC é o default técnico documentado para converter `acceptedAt` em data civil.
- A rotação operacional do token de serviço dedicado ainda precisa ser definida pela infraestrutura. Cabeçalho, rotas e transporte já estão definidos tecnicamente em RF-013.

## Critérios de Sucesso *(obrigatório)*

### Resultados Mensuráveis

- **CS-001**: Em uma bateria de cenários com logs, traces, múltiplos tenants e múltiplos produtos, 100% dos registros válidos reconhecidos são refletidos somente na combinação correta de tenant, produto e domínio.
- **CS-002**: Em lotes mistos avaliados, 100% dos registros inválidos são excluídos da contagem e dos bytes medidos.
- **CS-003**: Em cenários que não alcançam todas as confirmações exigidas, 0 registros são reconhecidos como volume aceito antes do ponto de reconhecimento definido.
- **CS-004**: Em 100% dos reenvios idênticos do mesmo lote ocorridos dentro de sete dias, o `batchId`/`usageFactId` impede nova cobrança de contagem e bytes; em 100% dos lotes novos e diferentes avaliados, contagem e bytes integrais — inclusive registros já medidos e envelope — são agregados.
- **CS-005**: Em todos os cenários de conversão avaliados, 100% dos valores de GB usam base decimal.
- **CS-006**: Em 100% dos cenários avaliados de indisponibilidade do Core após a aceitação, a ingestão é confirmada ao exportador, o fato de uso permanece durável e a sincronização posterior não duplica a agregação no Core.

## Premissas

- O Collector já identifica o tenant, o produto e o domínio de cada registro que processa.
- O OpenObserve, o stream de processamento e a DLQ fazem parte do fluxo atual de aceitação de registros.
- O Core é responsável por criar o modelo de ciclo do plano e agregar o uso: franquia mensal, ciclo semanal para plano semanal, cobrança mês a mês para plano semestral e início na data do primeiro pagamento. A nova entidade de plano/assinatura terá essa data obrigatória, preenchida por dependência externa.
- O Plano de Monetização é a referência para o uso de GB em base decimal.
- O canal de sincronização usa as rotas internas e o cabeçalho dedicados de RF-013. A rotação do token continua como decisão operacional de infraestrutura, preservando as regras de deduplicação por lote, persistência durável, retry e idempotência.

## Pendências para a próxima implementação

Itens deixados de propósito para uma implementação seguinte, sem bloquear o merge desta entrega:

> **Nota histórica — 2026-11-10 (America/Sao_Paulo):** RF-011 descreve corretamente o
> escopo histórico da medição: a entrega da spec005 não cria bloqueio nem reserva de cota.
> Michel decidiu em 10/11/2026 incluir a recusa sem saldo/ciclo pago na mesma LLT-186, com
> desenho separado em `specs/006-ingest-quota-reservation`; ainda não está implementado.
> **Atualização histórica — 11/11/2026:** a spec006 também tornou obrigatório o refund
> idempotente via outbox para falha de escrita/publicação após o débito; a medição desta spec
> continua sendo a fonte dos bytes canônicos, sem segunda contagem.

1. **Ensaio ponta a ponta (T046)**: o ensaio E2E do `quickstart.md` com Collector e Core juntos ainda não foi executado (logs, traces, lote parcial, reenvio, Core 503/timeout e reinício).
2. **Durabilidade condicional do Redis**: com AOF `everysec`, o RPO após um HTTP 200 pode ser de até cerca de 1 s; a decisão operacional de usar `appendfsync always` não foi tomada. O script Lua é atômico, mas não faz rollback: um estado `AMBIGUOUS` depende do reconciliador e de intervenção operacional. Redis Cluster não é suportado pelo desenho atual das chaves e dos streams.
3. **Fonte de `firstPaymentDate`**: a data do primeiro pagamento é obrigatória na entidade de plano/assinatura do Core, mas a fonte externa que a preenche permanece indefinida.
4. **Rotação do token de serviço**: `LHAMALOG_COLLECTOR_CORE_TOKEN` (e o correspondente `LHAMALOG_CORE_USAGE_TOKEN` no Core) é provisionado por configuração ou segredo de ambiente; a rotação é tarefa operacional, não implementada nesta entrega.
5. **Testes de integração do Core pulados**: os 4 testes `CompanyProductIntegrationTests` do Core foram ignorados por falta de `TEST_MONGODB_URI`.
