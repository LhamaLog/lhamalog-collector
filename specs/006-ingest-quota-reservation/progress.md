# Progresso — spec006 (LLT-186)

Atualizado em 11/11/2026 09:03 (America/Sao_Paulo). Histórico detalhado em `progress-notes.md`.

## Estado atual
- Clarify fechado com as decisões consolidadas: reserva em paralelo à sanitização sobre os bytes como recebidos; débito via `confirm` idempotente antes de OpenObserve/stream/DLQ; sem saldo -> 429 RESOURCE_EXHAUSTED; sem plano ou Core fora -> 503 UNAVAILABLE; Retry-After 120; falha fechada; lote acima do saldo recusado inteiro; logs+spans no mesmo saldo em bytes (plano em GB decimal); reembolso idempotente via Core com outbox Mongo durável; TTL 20 min só como rede de segurança; nenhuma participação da Varredura; SALDO_BAIXO média, 20%/5%, uma vez por limiar por ciclo, Dono+Administrador WEB+E-MAIL além da matriz.
- A1: inexistente desde a rodada 4.
- Analyze (07:26, posterior às decisões finais; spec/plan/tasks sem alteração depois): CRITICAL 0, HIGH 0, MEDIUM 0, LOW 0; 46 tarefas; 27/27 itens cobertos; portão liberado.
- Decisões de negócio em aberto: nenhuma.
- Implementação: concluída (46/46 tarefas [X]). Verify final 08:58: Collector 219 testes e Core 47 testes, 0 falhas.
- E2E: concluído com evidências em `e2e-spec006-evidence.md` e `e2e-spec006-evidence-complementar.md`; teardown feito.
- Fora destes repositórios: resolução da matriz e entrega WEB/E-MAIL (módulo Notificação); módulo real de sanitização/ocultação (hoje porta identidade `BatchSanitizer`); fonte externa de `allowanceGigabytes`/`firstPaymentDate`.

## Próximos passos
- Revisão do Michel e commit/push somente com autorização explícita.