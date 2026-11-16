# Research — 007-plan-rate-and-size-limits (LLT-183)

## 1. Algoritmo de rate limit

- **Decision**: Janela fixa de 1 segundo por tenant; script Lua atômico `INCR` + `EXPIRE` (TTL 2 s); chave `lhamalog:ratelimit:{tenantId}:{epochSecond}`; limite 20; contar cada requisição OTLP (HTTP/gRPC, logs/traces) após autenticação.
- **Rationale**: Simples de explicar; Retry-After pode ser propriedade fixa; Redis já no MVP; multi-instância coordenada.
- **Alternatives considered**: Token bucket (melhor burst, Retry-After mais complexo); contador só em memória (quebra com réplicas); req/s + bytes/s (Michel pediu só req/s).

## 2. Onde medir tamanho (comprimido vs decodificado)

- **Decision**: Medir o **payload descomprimido/decodificado**, alinhado a `lhamalog.otlp.max-message-size` (hoje conta bytes após gzip). Propriedade nova `lhamalog.otlp.max-batch-size` default 5MB. Ordem: taxa → leitura/checagem de tamanho → reserva.
- **Rationale (HTTP)**: `OtlpPayload.read()` já valida Content-Length (não-gzip) e conta bytes descomprimidos (gzip). Rate no `BatchIngestion` após auth e **antes** de `payload.read()` garante D8. Size na/após leitura, antes da reserva.
- **Rationale (gRPC)**: Netty aplica `maxInboundMessageSize` (64 MB) antes do método de serviço — inevitável. Após auth+taxa no use case, checar tamanho da mensagem já materializada (ex.: `getSerializedSize()` do protobuf ou tamanho equivalente) contra 5 MB. Mantém “taxa antes de tamanho de aplicação” mesmo com frame já na memória.
- **Alternatives considered**: Medir bytes comprimidos na wire (Content-Length gzip enganoso vs “lote 5 MB”); size antes de auth (quebra FR-015 / ordem pós-token).

## 3. Distinção 429 taxa vs 429 saldo

- **Decision**: Mesmo código HTTP 429 / gRPC RESOURCE_EXHAUSTED; Retry-After diferente (taxa = propriedade D5 provisório 300; saldo = 120); métricas/logs com `reason=rate_limit` vs `reason=insufficient_balance` (nomes EN no código).
- **Rationale**: Exporters OTLP entendem 429 genérico; operação precisa do motivo.
- **Alternatives considered**: Código HTTP distinto para taxa (fora do padrão OTLP/retry comum).

## 4. Retry-After Core indisponível / sem plano

- **Decision**: Alterar apenas o caminho `QuotaUnavailable` / NO_PLAN / Core down de 120 → propriedade default **300 s**. Manter saldo insuficiente em **120 s** (spec006).
- **Rationale**: D4 do Michel (5 min). “Até 5 vezes” não interpretado — [A CONFIRMAR].
- **Alternatives considered**: Mudar todos os 120 da spec006 (rejeitado — saldo tem semântica própria).

## 5. Redis down no rate limiter

- **Decision (provisória)**: Fail-open configurável + métrica/WARN; default fail-open; [A CONFIRMAR Q13].
- **Rationale**: Sem Redis, Streams também falham; fail-closed gera 503/429 artificiais antes da falha real de publicação.
- **Alternatives considered**: Fail-closed imediato; circuit breaker acoplado à saúde dos Streams.

## 6. SUSPENSA e overage no Core

- **Decision**: `PlanSubscription.status` (ATIVA/SUSPENSA/INADIMPLENTE) default ATIVA; `overageEnabled` boolean default false. Reserve: SUSPENSA → outcome `SUSPENDED` HTTP **403**. Overage: se enabled e saldo insuficiente, autorizar e acumular `overageBytes` (ou campo equivalente) sem implementar cobrança. INADIMPLENTE: enum presente; ingestão como ATIVA até Q14.
- **Rationale**: D6/D7; compatibilidade com documentos Mongo legados; Collector hoje mapearia 403 genérico para Unavailable — precisa ramo explícito.
- **Alternatives considered**: Reusar 422 NO_PLAN (vira 503 OTLP — errado para suspensão); cobrança Asaas nesta feature (fora de escopo).

## 7. Fonte dos números 20 req/s e 5 MB

- **Decision**: Propriedades Collector no MVP; não preencher TabelaPrecificacao vazia.
- **Rationale**: ADR_131 rateLimit pendente; Michel deu números absolutos em 16/11/2026.
- **Alternatives considered**: Hardcode sem propriedade (pior para ops); inventar matriz por plano comercial.

## 8. Ordem taxa → tamanho → reserva

- **Decision**: Seguir D8 do Michel (não a recomendação técnica anterior tamanho→taxa).
- **Rationale**: Decisão de produto; rate barato (Redis) pode evitar ler body grande quando já throttled (HTTP).
- **Alternatives considered**: size→rate (recomendação 11/11); reserve primeiro (rejeitado).
