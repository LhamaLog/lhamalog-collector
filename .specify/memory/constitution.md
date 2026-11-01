<!--
Sync Impact Report
- Version change: 1.0.0 -> 2.0.0 (MAJOR: princípio II removido e redefinido)
- Modified principles:
  - II. Autoria humana mínima de 40% -> II. IA pode implementar 100%, com revisão e domínio do desenvolvedor
- Added sections: nenhuma
- Removed sections: nenhuma (a regra 60/40 e as marcas [IA]/[Michel] saem do princípio II e do fluxo de merge)
- Templates requiring updates:
  - .specify/templates/plan-template.md: ✅ sem referência a 60/40
  - .specify/templates/spec-template.md: ✅ sem referência a 60/40
  - .specify/templates/tasks-template.md: ✅ sem referência a 60/40
  - specs/001-otlp-receiver/ (plan.md, research.md seção 11, tasks.md): ✅ atualizados
- Follow-up TODOs: nenhum
-->
# LhamaLog Collector Constitution

## Core Principles

### I. SpecKit em toda feature (NON-NEGOTIABLE)

Toda feature MUST seguir o SpecKit nesta ordem: specify, plan, tasks e implement.
Código de feature sem spec, plan e tasks correspondentes é inválido e MUST NOT
entrar na branch principal.

Rationale: o SpecKit é o contrato de escopo. Implementar antes de fechar
specify, plan e tasks mistura entrega com requisito ainda aberto.

### II. IA pode implementar 100%, com revisão e domínio do desenvolvedor (NON-NEGOTIABLE)

A IA MAY implementar 100% do código de uma feature, inclusive a lógica de
negócio. Toda implementação MUST seguir o Spec Kit (princípio I): nenhuma linha
de código entra sem spec, plan e tasks que a descrevam. O desenvolvedor MUST
revisar cada entrega e manter o domínio do que foi implementado; a IA MUST
explicar ao desenvolvedor o que implementou, por qual perspectiva e com quais
decisões, e as decisões MUST ficar registradas nos artefatos do Spec Kit
(research, plan, tasks). Tasks não levam marca de autoria.

Rationale: a regra 60/40 foi derrubada no alinhamento de gestores de
2026-10-31. O controle passa a ser o Spec Kit como documentação viva e a
revisão do desenvolvedor, que precisa entender e conseguir manter o código.

### III. Testes antes do merge (NON-NEGOTIABLE)

Nenhuma alteração MUST ser mergeada sem testes que cubram o comportamento novo
ou alterado. Build e testes MUST rodar na máquina do desenvolvedor, com o
Maven Wrapper (`./mvnw` ou `.\mvnw.cmd`), e MUST passar antes do merge. Resultado
que não foi reproduzido localmente não serve como evidência.

Rationale: a suíte local é o portão de qualidade. Merge sem teste verde deixa
a falha para o próximo desenvolvedor.

### IV. Inglês no código, português na documentação

Identificadores, nomes de pacotes, classes, métodos, campos, endpoints e
comentários no código MUST estar em inglês. Documentação para pessoas — README,
ADRs, specs, planos, tasks, esta constituição e descrições de commit — MUST
estar em português.

Rationale: o código segue o vocabulário da plataforma e das bibliotecas. Quem
lê o repositório trabalha em português.

### V. Segredos fora do código e do commit (NON-NEGOTIABLE)

Tokens, senhas, chaves de API e qualquer segredo MUST NOT aparecer em código,
teste, documentação versionada, exemplo commitado ou histórico do Git.
Configuração sensível MUST vir de variável de ambiente ou de arquivo local
ignorado pelo Git. Se um segredo for commitado, o trabalho MUST parar até a
rotação do segredo e a remoção do valor do histórico.

Rationale: o collector autentica produtos com token Bearer. Segredo no
repositório vaza para todo clone e para o histórico.

## Stack tecnológico

O LhamaLog Collector MUST ser implementado em Java 25, Spring Boot 4.1.1 e
Maven. O build MUST usar o Maven Wrapper do repositório (`./mvnw` ou
`.\mvnw.cmd`). O pacote base MUST ser `br.com.lhamalog.collector` e o groupId
MUST ser `br.com.lhamalog`.

Mudança de versão de Java, Spring Boot ou do sistema de build é emenda desta
constituição e MUST ser registrada aqui antes de entrar no código.

## Fluxo de desenvolvimento

- A branch de feature MUST seguir `feature/<chave-jira>-<descricao>`, com a
  chave do ticket no Jira e a descrição curta em português, sem espaços.
  Exemplo: `feature/LL-12-ingestao-otlp`.
- A mensagem de commit MUST seguir `<chave-jira>: <descricao>`.
  Exemplo: `LL-12: persiste log no Elasticsearch`.
- O merge MUST confirmar, nesta ordem: SpecKit completo, revisão do
  desenvolvedor sobre o que a IA implementou, build e testes locais verdes,
  branch e commit no padrão, e ausência de segredos.

## Governance

Esta constituição prevalece sobre prática informal do repositório. Emenda MUST
ser feita neste arquivo, com versão semântica e data `Last Amended`.

Versionamento:

- MAJOR: remoção ou redefinição incompatível de princípio.
- MINOR: princípio ou seção nova, ou expansão material de uma regra.
- PATCH: clarificação, redação ou correção que não muda a regra.

Revisão de conformidade: specify, plan, tasks e o review de merge MUST checar
os cinco princípios, o stack e o fluxo de desenvolvimento. Violação bloqueia o
merge até a correção ou até uma emenda explícita desta constituição.

**Version**: 2.0.0 | **Ratified**: 2026-10-30 | **Last Amended**: 2026-10-31
