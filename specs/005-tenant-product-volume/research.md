# Pesquisa: Volume por Tenant e Produto (LLT-186)

## D1. Protocolo de reconhecimento e janelas de crash

- **Decisão**: a evidência única por fato é `usage:fact:{usageFactId}`, em qualquer rota. O Lua recebe
  explicitamente `KEYS[1]` (stream alvo) e `KEYS[2]` (chave do fato): `DONE:*` retorna `DONE` sem `XADD`;
  `ABANDONED` retorna `ABANDONED` sem `XADD`; ausente grava `CLAIMED:{token}`, executa `XADD` e grava
  `DONE:{route}:{entryId}`. Depois, Mongo sofre CAS `PENDING → ACCEPTED`.
- **Racional**: Lua é atômico, mas não faz rollback de escrita. Erro no `XADD` deixa somente `CLAIMED`, prova
  que não há `DONE`; erro no `SET DONE` depois de `XADD` deixa `CLAIMED` com entrada existente, portanto
  estado `AMBIGUOUS`. Esse último caso só é esperado sob falha interna do Redis (por exemplo OOM no meio do
  script); scripts que escrevem normalmente são recusados no início sob OOM. A premissa deve ser validada
  na versão Redis usada, com teste de OOM/falha injetada.
- **Alternativas consideradas**: varrer stream/DLQ (rejeitada: truncamento não é prova); usar tempo como
  prova (rejeitada: não cria fencing); apagar a evidência após aceitar (rejeitada: reabre publicação).

| Janela | Resultado honesto |
|---|---|
| Falha ao gravar `PENDING` | Responder `503`; nada foi aceito. |
| `PENDING` gravado, main e DLQ retornam erro sem `DONE` | Responder `503`; reconciliador usa CAS e abandona somente se a chave estiver ausente. |
| `DONE` existe, Mongo não marcou `ACCEPTED` | Responder `200`; reconciliador transforma o candidato em `ACCEPTED`. |
| `CLAIMED` | `AMBIGUOUS`, alerta e resolução operacional; grace apenas evita interferir em requisição em voo. |
| Resposta ao exportador perdida após aceitação | Reenvio encontra o mesmo `usageFactId` e não duplica o fato. |
| Core indisponível após `ACCEPTED` | Exportador recebe sucesso; o sincronizador HTTP repete até 2xx. |

## D2. Outbox durável no MongoDB existente

- **Decisão**: usar MongoDB com índice único em `usageFactId` e estados `PENDING`, `ACCEPTED`, `SYNCED`,
  `ABANDONED` e `AMBIGUOUS`. Conteúdo de `PENDING` (bytes, contagens e `recordIds`) é imutável: upsert com
  mesmo id e conteúdo divergente registra erro e nunca sobrescreve. Transições são CAS; `PENDING` não é
  sincronizável/faturável, `ACCEPTED` é sincronizável e `SYNCED` recebeu 2xx. Lease, tentativa e backoff são
  atributos de sincronização.
- **Racional**: o índice torna o reenvio idempotente, preserva durabilidade através de reinício e o Mongo é a
  fonte de verdade da transição de faturabilidade.
- **Alternativas consideradas**: publicar um evento somente no Redis (rejeitada: retenção/durabilidade não são
  garantidas); chamada HTTP síncrona ao Core (rejeitada: Core offline bloquearia a confirmação); nova base de
  dados (rejeitada: aumenta operação sem necessidade demonstrada).

## D3. Identidade, deduplicação e semântica de bytes

- **Decisão**: `usageFactId = SHA-256(tenantId, productId, domain, batchId)`. `batchId` é SHA-256 sobre
  `(tenantId, productId, domain, lista ordenada dos recordIds aceitos)`. `RecordIds` atual confirma que cada
  `recordId` deriva da serialização protobuf de resource, scope e registro, com tenant/produto e ocorrência;
  a identidade é independente de JSON/protobuf, HTTP/gRPC e compressão. Somente o reenvio idêntico do mesmo
  lote, identificado pelo mesmo `batchId`/`usageFactId`, não duplica enquanto a chave estiver válida. Um lote
  novo e diferente conta integralmente, inclusive registros já medidos e envelope; não há deduplicação por
  `recordId`, e um mesmo registro pode ser cobrado mais de uma vez em lotes diferentes.
- **Racional**: fixa a idempotência no lote e elimina a regra de sobreposição parcial.
- **Retenção**: a chave de deduplicação expira após sete dias. Depois disso, reenvio do mesmo lote pode ser
  contado novamente; não assumir 40 dias nem retenção indefinida.
- **Alternativas consideradas**: deduplicar fatos somente por `recordId` (rejeitada: perde o fato/lote).

## D4. Tamanho OTLP canônico

- **Decisão**: antes de acrescentar metadados internos, reconstruir `ExportLogsServiceRequest` ou
  `ExportTraceServiceRequest` só com registros válidos, preservando a ordem e hierarquia originais
  resource/scope/envelope e eliminando grupos vazios. Serializar deterministicamente a mensagem protobuf e
  medir seu tamanho. JSON e protobuf em HTTP/gRPC convergem para essa mesma mensagem; compressão é ignorada.
- **Racional**: inclui resource, scope e envelope sem depender da codificação ou compressão de transporte.
  Lotes reagrupados podem ter overhead diferente; não se alega canonicidade entre rearranjos.
- **Alternativas consideradas**: tamanho do corpo HTTP/gRPC recebido (rejeitada: varia com compressão e
  codificação); soma de `record.getSerializedSize()` atual (rejeitada: exclui resource, scope e envelope).

## D5. Entrega ao Core e token de serviço dedicado

- **Decisão**: a API interna aceita o Collector exclusivamente por token de serviço dedicado em
  `X-LhamaLog-Service-Token: <token>` e nunca aceita token Clerk. O Collector lê
  `LHAMALOG_COLLECTOR_CORE_TOKEN`; o Core lê `LHAMALOG_CORE_USAGE_TOKEN`. Ambos são configuração/segredo de
  ambiente; testes injetam valor de teste e nenhum valor é gravado em código, teste ou documentação.
- **Racional**: token de usuário Clerk não representa identidade de máquina, e um cabeçalho dedicado evita
  confusão com autenticação de usuário.
- **Contrato**: o envio é `POST /internal/usage-facts`; as consultas internas são
  `GET /internal/usage-facts?tenantId&productId&from&to` (janela `[from,to)`) e
  `GET /internal/usage-facts/current-cycle?tenantId&productId` (agregado do ciclo vigente segundo
  [RF-006 e RF-014](spec.md#requisitos-funcionais)).
  A rotação do token é decisão operacional de infraestrutura pendente.

## D6. Retry, reinício, reconciliação e limpeza

- **Decisão**: no startup e periodicamente, para cada `PENDING` após grace de liveness, o reconciliador usa
  CAS no Redis: ausente → `SET ABANDONED` (fencing do dispatcher lento); `DONE:*` → CAS Mongo `ACCEPTED`;
  `ABANDONED` → CAS Mongo `ABANDONED`; `CLAIMED:*` → CAS Mongo `AMBIGUOUS`, alerta operacional. Grace não
  é prova de ausência. A chave de deduplicação expira após sete dias; após isso, o reenvio do mesmo lote pode
  ser contado novamente. O sincronizador usa lease/backoff/jitter e marca `SYNCED` após 2xx.
- **Racional**: o fencing garante que um dispatcher atrasado lê `ABANDONED` e não executa `XADD`; somente
  estados Redis são evidência.
- **Alternativas consideradas**: exactly-once por lock distribuído (rejeitada: não resolve perda de resposta
  HTTP); scan do stream (rejeitada: `MAXLEN`); reapresentação obrigatória pelo exporter (rejeitada: falha em
  crash sem reenvio).

## D7. Estratégia de teste

- **Decisão**: escrever primeiro testes unitários de serialização canônica/identidade e testes de integração
  Mongo para unicidade, reinício e retry; completar com aceitação HTTP e gRPC para logs/traces.
- **Racional**: as falhas relevantes cruzam armazenamento, transporte e os limites de confirmação.
- **Cobertura mínima**: mesmo lote reenviado dentro de sete dias; lote novo e diferente com registro já
  medido, que conta integralmente; reenvio do mesmo lote após sete dias; lote parcialmente aceito;
  Core offline; timeout/resposta repetida; reinício com fato pendente; retries e lease; tamanho de envelope
  para logs e traces por HTTP protobuf, HTTP JSON, gRPC; confirmação por stream e por DLQ.

## D8. Modelo de ciclo do plano no Core

- **Decisão**: a LLT-186 cria no Core o modelo de ciclo do plano. A franquia é mensal; plano semanal usa
  ciclo semanal; plano semestral é cobrado mês a mês; e o ciclo começa na data obrigatória do primeiro
  pagamento da nova entidade de plano/assinatura.
- **Dependências**: o Core ainda não possui a entidade de plano/assinatura; o preenchimento de
  `firstPaymentDate` é externo a esta feature. Para converter `acceptedAt` em data civil, UTC é o default
  técnico documentado, não uma decisão de negócio de Michel.

## D9. Durabilidade Redis e RPO

- **Decisão**: não alterar a configuração Redis compartilhada, não exigir Redis Cluster e manter o protocolo
  `CLAIMED`/`DONE`/`ABANDONED`/`AMBIGUOUS` e Lua sem rollback.
- **Racional**: com AOF `appendfsync everysec` padrão, uma queda após resposta `200` pode perder até
  aproximadamente um segundo de evidência e stream, resultando em subcontagem. O protocolo ainda impede
  publicação duplicada e preserva a recuperação segura das demais janelas de crash; não transforma a
  garantia em exactly-once.
- **Alternativas consideradas**: aplicar `appendfsync always` (reduz a janela, mas aumenta latência e fica
  como decisão operacional pendente de infraestrutura); exigir Redis Cluster (rejeitada, pois não é requisito
  desta feature e não resolve o RPO).
