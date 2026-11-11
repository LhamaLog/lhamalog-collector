# Quickstart: validar volume por tenant e produto (LLT-186)

Roteiro de validação para a implementação futura. Regras do domínio estão em
[data-model.md](data-model.md) e a interface com o Core em
[contracts/usage-facts-api.md](contracts/usage-facts-api.md).

## Pré-requisitos

- JDK 25, Docker Desktop e Maven Wrapper;
- `infra/local/.env` configurado fora do controle de versão;
- MongoDB, Redis e OpenObserve do ambiente local;
- stub autenticado do Core para `POST /internal/usage-facts` e as consultas internas do contrato.

O Collector recebe `LHAMALOG_COLLECTOR_CORE_TOKEN` e o Core recebe `LHAMALOG_CORE_USAGE_TOKEN` por
configuração/segredo de ambiente. O stub aceita apenas `X-LhamaLog-Service-Token`; Clerk e `Authorization`
não são aceitos. Testes injetam um valor de teste e não usam nem geram segredo real.

Para o ensaio local, injete o mesmo valor de teste nos dois processos por mecanismo local não versionado.
O stub deve rejeitar a ausência do cabeçalho ou qualquer valor distinto e registrar apenas o
`usageFactId`, o status e a contagem — nunca o cabeçalho, payload OTLP ou credenciais.

Para trocar esses tokens em ambiente operacional, siga o
[runbook de rotação](token-rotation-runbook.md).

## Testes automatizados

```powershell
.\mvnw.cmd clean verify
```

Última validação local do Collector: `.\mvnw.cmd verify` em 2026-11-09, concluído com sucesso
(código de saída 0). A suíte iniciou MongoDB, Redis, OpenObserve e Grafana por Testcontainers.
Os testes específicos de uso validaram identidade determinística, transições da outbox, reconciliação,
contrato HTTP com o Core e backoff/suspensão do trabalhador. O ensaio E2E completo permanece pendente
da ligação da medição canônica e do protocolo Redis/Lua ao caminho de ingestão.

O conjunto precisa provar:

1. Logs e traces de tenants/produtos distintos geram fatos separados por tenant, produto e domínio.
2. Lote parcialmente válido produz fato somente para o subconjunto aceito, com a contagem e os bytes do
   envelope canônico reconstruído.
3. O mesmo lote reenviado gera o mesmo `usageFactId`; Mongo mantém um item de outbox e o Core não agrega duas
   vezes.
4. Lote novo e diferente, mesmo contendo registro já medido, eleva integralmente a contagem e os bytes,
   inclusive o envelope; não há deduplicação por `recordId`.
5. Reenvio idêntico do mesmo lote dentro de sete dias não altera contagem nem bytes; após sete dias, pode
   ser contado novamente.
6. Core offline, timeout e 5xx não impedem sucesso ao exportador depois de persistir a outbox; a tentativa é
   retomada com retry.
7. Reinício depois de persistir o fato e antes/depois de receber a resposta HTTP recupera item pendente ou
   repete a chamada com o mesmo `usageFactId` no corpo.
8. Falha ao gravar `PENDING` retorna `503`. Falha de OpenObserve, main e DLQ sem `DONE` também retorna `503`.
   Se `DONE` foi gravado e o CAS Mongo para `ACCEPTED` falhar, a resposta é `200` e a reconciliação conclui
   `ACCEPTED`.
9. O tamanho de logs e traces é idêntico para o mesmo conteúdo aceito enviado por HTTP protobuf, HTTP JSON e
   gRPC, e inclui resource, scope e envelope.
10. Teste de crash prova: erro no `XADD` deixa `CLAIMED`; erro após `XADD` deixa `CLAIMED` e cria
   `AMBIGUOUS`; CAS reconciliador ausente→`ABANDONED` impede `XADD` de dispatcher lento; reenvio concorrente
   produz um `XADD`.
11. `GET /internal/usage-facts` respeita a janela `[from,to)` e
    `GET /internal/usage-facts/current-cycle` retorna o agregado do ciclo vigente definido em
    [RF-006 e RF-014](spec.md#requisitos-funcionais).
    A consulta da janela responde `{ items, nextCursor }` com `limit`/`cursor` (padrão 50, máximo 100). O ciclo vigente responde `logs` e `traces` separados e `active: false` se a data civil UTC for anterior a `firstPaymentDate` (escolha técnica reversível).

## Ensaio local

1. Suba a infraestrutura e a aplicação:

```powershell
docker compose -f infra/local/compose.yaml up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Para medir volume no ensaio E2E (T046), o Collector precisa subir com `lhamalog.usage.enabled=true` de um
jeito que o JVM do `spring-boot:run` veja o valor (nao so a sessao do PowerShell). Alem de
`LHAMALOG_USAGE_ENABLED=true` e `LHAMALOG_COLLECTOR_CORE_TOKEN` no ambiente do processo (nunca em arquivo),
passe tambem `--lhamalog.usage.enabled=true` e a URL do Core em `-Dspring-boot.run.arguments`, e confirme no
log de startup `usage.enabled=true` e `Usage outbox worker scheduled`. Sem isso o OTLP continua 200 e o Core
fica com `items: []`.

O seed de `PlanSubscription.firstPaymentDate` deve ser meia-noite UTC (`2026-11-01T00:00:00Z`). O Core le
`LocalDate` em UTC para o ciclo civil nao recuar um dia em `America/Sao_Paulo`.

2. Cadastre apenas os dados de token/produto de desenvolvimento necessários e envie:
   - um lote de logs válido do produto A/tenant A;
   - um lote de traces válido do produto B/tenant B;
   - um lote de logs parcialmente inválido;
   - novamente o primeiro lote.
3. No Mongo, confira que a outbox possui um fato por lote/domínio aceito e que o reenvio não acrescentou outro
   documento com `usageFactId` diferente.
4. Faça o stub do Core responder 503 uma ou mais vezes, depois 201. Confira tentativas crescentes,
   `nextAttemptAt` reagendado e estado final `SYNCED`.
5. Faça o stub registrar duas requisições idênticas por timeout simulado. Confirme o mesmo `usageFactId`,
   o cabeçalho `X-LhamaLog-Service-Token` e que a agregação do stub trata a segunda como repetição.
6. Pare o Collector depois de persistir o candidato e antes da resposta simulada do Core; reinicie-o e
   confirme que a tentativa recuperada usa o mesmo `usageFactId`. Para o caminho Redis, verifique a
   evidência `DONE:*` para main ou DLQ; `CLAIMED:*` exige alerta e `ABANDONED` não pode publicar de novo.

## Resultado esperado

O exportador só recebe sucesso quando o lote foi aceito por OpenObserve e stream/DLQ possui `DONE`; a falha
posterior de CAS Mongo é recuperada pelo reconciliador. A indisponibilidade posterior do Core não muda esse
sucesso. Os bytes entregues são bytes
brutos do protobuf canônico; qualquer exibição em GB usa divisor decimal 1.000.000.000. O Core cria o modelo
de ciclo do plano com franquia mensal, ciclo semanal para plano semanal, cobrança mês a mês para plano
semestral e início na data do primeiro pagamento; o preenchimento externo dessa data obrigatória permanece
dependência. A deduplicação expira após sete dias. A garantia Redis é condicional: com AOF `everysec`,
pode haver RPO de até ~1 s após `200`; a infraestrutura pode avaliar `appendfsync always`, sem alteração
aplicada por esta feature.
