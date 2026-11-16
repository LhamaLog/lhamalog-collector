# Evidência da verificação local LLT-185 / spec 009 — stream por tenant e retenção por plano

Executado em 16/11/2026 16:44 (BRT) no Avell, com os jars dos working trees (sem commit). Segredos gerados por execução só no ambiente do processo.

## Preparação
- segredos gerados por execução, só no ambiente do processo (token de serviço length=64)
- seed Core: Tenant-Free.Acme/p-free plano FREE; tenant_pro/p-pro plano PRO; tenant-legacy/p-legacy sem planTier (documento antigo)
- Core (8081) e Collector (OTLP 4328/4327, quota e usage ligados) em execução, jars do working tree (sem commit)

## Ingestão de dois tenants com planos diferentes
- Tenant-Free.Acme (FREE) HTTP JSON: status=200 retry_after=None cid_echo=e2e-llt185-free
- tenant_pro (PRO) gRPC: grpc=OK
- tenant_pro (PRO) HTTP protobuf, 2º lote (cache): status=200 retry_after=None
- tenant-legacy (sem planTier) HTTP JSON: status=200 retry_after=None

## Streams no OpenObserve (API)
- stream `logs_tenant_free_acme_1d000cfd`: data_retention=7 doc_num=0
- stream `logs_tenant_legacy_b4a213ee`: data_retention=7 doc_num=0
- stream `logs_tenant_pro`: data_retention=30 doc_num=0
- registros em `logs_tenant_free_acme_1d000cfd` por tenantId: Tenant-Free.AcmeTenant-Free.Acme=2
- registros em `logs_tenant_legacy_b4a213ee` por tenantId: tenant-legacytenant-legacy=1
- registros em `logs_tenant_pro` por tenantId: tenant_protenant_pro=2
- linhas event=openobserve.stream_retention no Collector: 
- linhas event=quota.retention.* no Core: event=quota.retention.resolved=4, event=quota.retention.default_tier_applied=1

## Segurança dos logs
- segredos em claro nos logs: 0
- teardown: Mongo do Core removido
- teardown: llt185-live down -v

## Conclusão

- FREE → `data_retention=7`, PRO → `data_retention=30`.
- Assinatura antiga sem `planTier` → `data_retention=7` (FREE), conforme a decisão do Michel de 16/11/2026 16:36. O Core registrou `event=quota.retention.default_tier_applied`. Na execução anterior (15:19 BRT, antes da decisão) esse stream ficava com a retenção global.
- `doc_num=0` nas estatísticas é atraso de compactação do OpenObserve; a consulta SQL confirma os registros. O nome do tenant aparece repetido porque o script concatena `tenantid` e `tenantId` da resposta.
