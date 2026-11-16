# Runbook: rotação dos tokens de serviço

Este procedimento troca os tokens usados entre Collector e Core sem registrar seus valores em arquivos
versionados, logs ou comandos compartilhados.

## Procedimento

1. Gere um novo segredo com fonte criptograficamente segura e mantenha-o apenas no mecanismo de segredos
   aprovado para o ambiente.
2. Injete o novo segredo no processo Collector pela variável
   `LHAMALOG_COLLECTOR_CORE_TOKEN`.
3. Injete o mesmo novo segredo no processo Core pela variável
   `LHAMALOG_CORE_USAGE_TOKEN`.
4. Reinicie o Collector e o Core para que ambos os processos leiam as novas variáveis de ambiente.
5. Verifique, sem exibir o valor do segredo, que o Collector consegue autenticar chamadas ao Core com o
   cabeçalho `X-LhamaLog-Service-Token`.
6. Retire o token anterior do mecanismo de segredos e encerre qualquer processo remanescente que ainda o
   mantenha em memória ou no ambiente. Confirme que somente processos reiniciados permanecem em execução.

Não registre os tokens em comandos, arquivos de configuração versionados, capturas de tela ou logs.
