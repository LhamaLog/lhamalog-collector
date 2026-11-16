# Contrato: `retentionDays` em POST /internal/usage-reservations

**Consumidores**: Collector (este repo) e Core (repo paralelo — **não editar Core aqui**).
**Compatibilidade**: tolerant reader / tolerant writer.

## Resposta (autorização)

Em HTTP 200/201 com `outcome` ∈ {`AUTHORIZED`, `AUTHORIZED_UNLIMITED`} (e qualquer outro outcome futuro que autorize ingestão, inclusive overage):

```json
{
  "outcome": "AUTHORIZED",
  "reservationId": "quota:…",
  "expiresAt": "2026-11-16T15:00:00Z",
  "retentionDays": 30,
  "overageBytes": 0
}
```

| Campo | Tipo | Obrigatório | Semântica |
|-------|------|-------------|-----------|
| `retentionDays` | integer \| null \| omitido | não | Dias de retenção do **tenant** (máx. entre assinaturas com plano). Nome inglês no fio; mapeia ADR_131 `retencaoDias`. |

### Valores de plano (ADR_131)

FREE 7, STARTER 15, PRO 30, BUSINESS 90, ENTERPRISE 90.

### Agregação multi-produto (decidido pelo Michel em 16/11/2026 16:36)

Um tenant pode ter uma assinatura por produto com tiers diferentes. O stream OpenObserve é **por tenant**. Core MUST enviar o **máximo** `retencaoDias` entre assinaturas ativas com plano, para que nenhum produto perca dados. Assinaturas consideráveis: ATIVA e INADIMPLENTE (inclusive na carência); SUSPENSA não conta. Assinatura antiga **sem `planTier` conta como FREE (7 dias)**. O Core omite `retentionDays` (null) **somente** quando o tenant não tem nenhuma assinatura considerável.

## Resposta (não autoriza)

`INSUFFICIENT_BALANCE`, `NO_PLAN`, `SUSPENDED`, etc. **MAY** omitir `retentionDays`. Collector MUST ignorar o campo se o outcome não autorizar escrita.

## Parsing no Collector

- Ausente, JSON null, ou inteiro ≤ 0 → **unknown** → não chama settings.
- Inteiro > 0 → conhecido; após ingest OK, garantir settings.
- Campo desconhecido adicional → ignorar (já o faz para `overageBytes`).

## Compatibilidade

| Cenário | Comportamento |
|---------|---------------|
| Core antigo (sem campo) + Collector novo | unknown; stream por tenant; sem settings |
| Core novo + Collector antigo | campo ignorado pelo parser antigo |
| Ambos novos | stream por tenant + retention aplicada |

## Tarefas Core (marcadas no tasks.md)

Implementação no repo Core: calcular max `retencaoDias`, serializar `retentionDays` nas respostas autorizadas, testes de contrato.
