# Feature Specification: Recepção OTLP de logs e traces

**Feature Branch**: `001-otlp-receiver`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "Receber logs e traces no padrão OTLP por HTTP e gRPC, aceitando só requisições com token Bearer válido que identifique o produto; rejeitar registros inválidos e responder erro quando o destino estiver indisponível."

## Clarifications

### Session 2026-10-31

- Q: Qual identificador de tenant o collector grava em cada log e trace aceito? → A: ~~O tenant é o próprio produto~~ (substituída na sessão seguinte)

### Session 2026-10-31 (revisão)

- Q: Qual a relação entre productId e tenantId (ADR_121/ADR_168)? → A: Cada produto pertence a um tenant. O cadastro do produto guarda o tenantId. O collector resolve o tenantId pelo produto do token e grava tenantId e productId em cada log e span aceito; o conteúdo do lote não substitui nenhum dos dois

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Entregar logs do produto (Priority: P1)

O produto envia um lote de logs no padrão OpenTelemetry (OTLP) por HTTP ou por gRPC, apresentando um token Bearer válido. O collector só confirma o recebimento depois que o destino acusa que ficou com os registros aceitos, e esses registros ficam atribuídos ao produto do token.

**Why this priority**: Sem a entrega autenticada de logs não há ingestão. Este é o menor recorte que já gera valor para a plataforma.

**Independent Test**: Com um token válido de um produto conhecido e o destino disponível, enviar um lote de logs válido em cada canal (HTTP e gRPC) e conferir a confirmação, a atribuição ao produto e a presença dos registros no destino.

**Acceptance Scenarios**:

1. **Given** um produto com token Bearer válido e o destino disponível, **When** o produto envia um lote de logs OTLP válido por HTTP, **Then** a resposta confirma o aceite e o destino contém esses logs atribuídos a esse produto.
2. **Given** o mesmo produto e o destino disponível, **When** o produto envia um lote de logs OTLP válido por gRPC, **Then** o resultado é o mesmo do envio por HTTP: aceite confirmado e logs atribuídos ao produto do token.
3. **Given** um lote de logs válido cujo conteúdo nomeia outro produto ou outro tenant, **When** o envio usa o token do produto A, **Then** os logs aceitos ficam atribuídos ao produto A e ao tenant ao qual A pertence.
4. **Given** um produto autenticado, **When** ele envia um lote de logs bem formado e vazio, **Then** a resposta é sucesso de zero registros e nada é entregue ao destino.

---

### User Story 2 - Recusar envio inseguro, inválido ou sem destino (Priority: P1)

O remetente recebe um resultado explícito quando não prova o produto, quando o registro não pode ser aceito ou quando o destino não confirma o recebimento. Nada inválido ou não autorizado entra como dado aceito, e uma falha do destino não é reportada como sucesso.

**Why this priority**: O collector é um ponto de entrada alcançável por quem tiver o endereço. Aceitar token alheio, registro quebrado ou sucesso com destino fora corrompe a análise e esconde perda de dado.

**Independent Test**: Sem depender de traces, exercer as três falhas no canal de logs (HTTP e gRPC): token ausente ou desconhecido, registro inválido e destino indisponível. Conferir a classe do erro e que o destino não fica com o que foi recusado.

**Acceptance Scenarios**:

1. **Given** nenhum token, token vazio, token desconhecido ou token que não identifica um produto válido, **When** o remetente envia logs, **Then** a resposta recusa o envio como não autorizado, nenhum registro é aceito e o destino não recebe nada.
2. **Given** um token válido e um corpo que não é um lote OTLP de logs, **When** o remetente envia a requisição, **Then** a resposta rejeita o envio inteiro, nada é entregue ao destino e a falha é distinguível de "não autorizado" e de "destino indisponível".
3. **Given** um token válido, o destino disponível e um lote com registros válidos e inválidos, **When** o produto envia o lote, **Then** só os válidos são entregues ao destino, a resposta informa quantos foram rejeitados e o motivo, e os inválidos não aparecem como aceitos.
4. **Given** um token válido e um lote em que todos os registros são inválidos, **When** o produto envia o lote, **Then** a resposta é rejeição, nenhum registro é entregue e o envio não conta como sucesso.
5. **Given** um token válido e um lote de logs válido, **When** o destino está indisponível ou não confirma o recebimento, **Then** a resposta é erro de destino indisponível, o envio não conta como sucesso e a falha é distinguível de não autorizado e de registro inválido.
6. **Given** as mesmas falhas da história, **When** elas ocorrem por gRPC em vez de HTTP, **Then** a classe do resultado e o que deixa de ser aceito são os mesmos do HTTP.

---

### User Story 3 - Entregar traces do produto (Priority: P2)

O produto envia traces (o rastro de uma operação) no padrão OTLP, pelos mesmos canais e com as mesmas regras de token, de registro inválido e de destino usadas para logs.

**Why this priority**: Traces completam a telemetria pedida, mas a plataforma já obtém valor com logs autenticados e com as recusas da história 2.

**Independent Test**: Repetir o aceite e as três falhas usando somente traces, em HTTP e em gRPC, sem depender de um envio de logs no mesmo teste.

**Acceptance Scenarios**:

1. **Given** um produto com token válido e o destino de traces disponível, **When** o produto envia um lote de traces OTLP válido por HTTP e, em separado, por gRPC, **Then** cada resposta confirma o aceite e o destino contém esses traces atribuídos ao produto do token.
2. **Given** um envio de traces sem token válido, com registros inválidos ou com o destino indisponível, **When** o produto envia o lote, **Then** valem as mesmas classes de resultado da história 2, aplicadas a traces.

---

### Edge Cases

- Token do produto A não autoriza atribuir o envio ao produto B, mesmo que o lote nomeie B. O tenantId gravado é o do tenant de A, mesmo que o lote traga outro tenant.
- Lote misto, com destino disponível: aceita os registros válidos, rejeita os inválidos e informa quantidade e motivo. Lote em que todos são inválidos: rejeição total.
- Corpo ilegível ou de outro tipo de sinal (por exemplo métricas): rejeição do envio inteiro, sem entrega ao destino.
- Destino indisponível no meio do lote: a resposta é erro de destino, não confirmação. O remetente pode reenviar. Um reenvio do mesmo lote válido pode ser aceito de novo; esta feature não elimina duplicata.
- Envio acima do tamanho máximo configurado para o canal: rejeição do envio inteiro, sem entrega parcial.
- Lote vazio bem formado, com token válido: sucesso de zero registros.
- HTTP e gRPC, para a mesma situação, produzem a mesma classe de resultado e a mesma decisão do que é aceito.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O collector MUST aceitar logs no padrão OTLP enviados por HTTP e por gRPC quando a requisição traz um token Bearer válido de um produto conhecido.
- **FR-002**: O collector MUST aceitar traces no padrão OTLP enviados por HTTP e por gRPC sob as mesmas condições de token de FR-001.
- **FR-003**: O produto dos registros aceitos MUST ser exclusivamente o produto identificado pelo token. Um nome de produto presente só no conteúdo do lote MUST NOT mudar essa atribuição. O tenantId gravado MUST ser o do tenant ao qual esse produto pertence.
- **FR-004**: Requisição sem token Bearer, com token vazio, desconhecido ou que não identifica um produto válido MUST ser recusada como não autorizada. Nenhum registro dessa requisição MUST ser aceito ou entregue ao destino.
- **FR-005**: Um registro que não cumpre a estrutura mínima válida do sinal enviado MUST ser rejeitado e MUST NOT ser entregue como registro aceito.
- **FR-006**: Em um lote com registros válidos e inválidos, com o destino disponível, o collector MUST entregar somente os válidos e a resposta MUST informar a quantidade rejeitada e o motivo. Os inválidos MUST NOT constar como aceitos.
- **FR-007**: Se todos os registros do lote forem inválidos, ou se o corpo não puder ser interpretado como lote OTLP do sinal pedido, o collector MUST rejeitar o envio inteiro e MUST NOT entregar nada ao destino.
- **FR-008**: O collector MUST confirmar sucesso somente quando cada registro aceito tiver sido recebido pelo destino.
- **FR-009**: Se o destino estiver indisponível ou não confirmar o recebimento, a resposta MUST ser erro de destino indisponível e o envio MUST NOT ser reportado como sucesso.
- **FR-010**: As classes de resultado não autorizado, registro ou lote inválido e destino indisponível MUST ser distinguíveis entre si na resposta ao remetente.
- **FR-011**: HTTP e gRPC MUST aplicar as mesmas regras de aceite, rejeição, atribuição ao produto e erro de destino diante da mesma situação.
- **FR-012**: Sinais que não sejam logs nem traces MUST ser recusados, sem entrega ao destino.
- **FR-013**: Um lote bem formado e vazio, com token válido, MUST ser respondido como sucesso de zero registros e MUST NOT gerar entrega ao destino.
- **FR-014**: Um envio acima do tamanho máximo configurado para o canal MUST ser rejeitado por inteiro, sem entrega parcial.
- **FR-015**: Um token MUST ser considerado válido somente quando está ativo, não foi revogado, não expirou (ou não tem prazo) e o produto ao qual pertence existe. Qualquer outro caso cai em FR-004.
- **FR-016**: Um produto MAY ter mais de um token válido ao mesmo tempo. Cada token pertence a exatamente um produto.
- **FR-017**: Um token revogado MUST continuar registrado como revogado, com o momento da revogação, e MUST NOT ser apagado. A remoção definitiva de um token só ocorre junto com a remoção do produto ao qual pertence. Cadastro, rotação e revogação acontecem fora do collector; o collector só consulta.
- **FR-018**: Os registros aceitos MUST ser gravados com isolamento por tenant, como exigem a ADR_121 e a ADR_168. Cada produto pertence a exatamente um tenant. O collector MUST resolver o tenantId pelo produto do token e MUST gravar tenantId e productId em cada log e span aceito. Um tenantId ou productId presente só no lote MUST NOT substituir nenhum dos dois.

### Key Entities *(include if feature involves data)*

- **Produto**: origem da telemetria. É identificado só pelo token Bearer válido, não pelo texto interno do lote.
- **Token Bearer**: credencial apresentada em cada envio. Quando válida, aponta para exatamente um produto. O valor do token não faz parte desta especificação e não deve ser documentado no repositório.
- **Token do produto (ProductToken)**: registro de um token ligado a um produto, separado do cadastro do produto. Um produto tem um ou mais tokens. Guarda só o hash do token, a situação (ativo ou revogado), quando foi criado, quando foi revogado e, opcionalmente, quando expira. Nunca é apagado ao ser revogado; só sai junto com o produto.
- **Tenant**: unidade de isolamento dos dados no armazenamento de telemetria (ADR_121/ADR_168). Cada produto pertence a um tenant; o cadastro do produto guarda o tenantId. O tenantId gravado é o do produto identificado pelo token.
- **Lote de logs**: conjunto de registros de log no padrão OTLP enviado em uma requisição.
- **Lote de traces**: conjunto de registros de trace no padrão OTLP enviado em uma requisição.
- **Registro**: item individual do lote. Pode ser aceito ou rejeitado. Só o aceito segue para o destino, ligado ao produto do token, com o tenantId do tenant desse produto.
- **Destino**: quem deve ficar com os registros aceitos e confirmar o recebimento. Pela ADR_168, logs e traces têm o mesmo armazenamento de telemetria, em áreas separadas por sinal. Esta feature não define como o destino armazena, busca ou analisa esses registros.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em um ensaio com os dois canais, 100% dos lotes válidos de logs e de traces enviados com token válido e destino disponível recebem confirmação, 100% desses registros aceitos ficam atribuídos ao produto do token e 100% têm o tenantId do produto desse token.
- **SC-002**: Em um ensaio de requisições sem token válido, 100% são recusadas como não autorizadas e 0% dos seus registros aparecem como aceitos no destino.
- **SC-003**: Em um ensaio com o destino indisponível, 100% dos envios válidos e autenticados recebem erro de destino e 0% são confirmados como sucesso.
- **SC-004**: Em lotes mistos com destino disponível, 100% dos registros inválidos são informados como rejeitados e 100% dos registros aceitos são somente os válidos que o destino confirmou.
- **SC-005**: Em uma amostra com tokens de produtos diferentes e conteúdo que nomeia outro produto ou outro tenant, 100% dos registros aceitos permanecem atribuídos ao produto do token usado e 100% têm o tenantId do produto desse token.
- **SC-006**: Um avaliador que vê apenas a resposta ao remetente classifica corretamente, em 100% dos casos de um roteiro com as três falhas, se a causa foi não autorizado, dado inválido ou destino indisponível.
- **SC-007**: 100% das requisições do ensaio terminam com um resultado explícito de aceite, rejeição ou erro de destino. Nenhuma fica sem resultado.

## Assumptions

- Os produtos já emitem logs e traces no padrão OTLP e já possuem ao menos um token Bearer por produto. A emissão, a rotação e o cadastro do token estão fora desta feature; o collector só valida o token que recebe.
- "Token válido" significa token reconhecido, ativo, sem revogação, vigente e ligado a exatamente um produto existente. Token revogado, expirado, desconhecido ou de produto inexistente é inválido e cai em FR-004.
- O collector é alcançável por quem souber o endereço. A única autorização de envio nesta feature é o token Bearer do produto.
- Logs e traces vão para o mesmo armazenamento de telemetria definido na ADR_168, cada sinal na sua área configurada. Cada sinal só é confirmado se a área daquele sinal acusar o recebimento.
- Lote misto com destino saudável tem aceite parcial dos registros válidos. Isso segue o comportamento usual de exportação OTLP e a regra de rejeitar o registro inválido sem descartar o válido.
- Falha do destino no meio do lote não é sucesso. O remetente reenvia. A feature não deduplica reenvios; o destino pode receber a mesma carga mais de uma vez.
- Métricas e qualquer sinal além de logs e traces estão fora de escopo.
- Consulta, busca, análise, retenção e alertas sobre os registros aceitos estão fora de escopo.
- O tamanho máximo do envio é configuração operacional, igual nos dois canais. O número em si não é regra de negócio desta feature; a regra é rejeitar por inteiro o que passar desse teto.
- Cada produto pertence a um tenant, registrado no cadastro do produto. O tenantId gravado em cada registro aceito é o do produto do token. Um tenantId presente só no lote não vale. Produto sem tenantId é cadastro inválido e o token não é aceito.
- Exemplos, testes e documentação desta feature não incluem valor real de token ou de outro segredo.
