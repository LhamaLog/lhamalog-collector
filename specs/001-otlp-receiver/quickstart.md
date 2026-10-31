# Quickstart: Recepção OTLP de logs e traces

Guia para provar a feature depois da implementação. Os cenários detalhados
estão em [spec.md](spec.md). Códigos e caminhos estão em
[contracts/otlp-export.md](contracts/otlp-export.md).

Ainda não há receptor no código. Os comandos abaixo são o roteiro de
validação, não um resultado já observado.

## Pré-requisitos

- JDK 25
- Docker, para o compose local e para os Testcontainers
- Maven Wrapper do repositório (`.\mvnw.cmd` no Windows, `./mvnw` no Linux)

Não grave o token escolhido em arquivo commitado. Gere-o na sessão do terminal.

## Testes automatizados

Na raiz do repositório:

```powershell
.\mvnw.cmd test
```

O que precisa passar:

1. Lote válido de logs por HTTP e por gRPC, com token ativo, fica no stream
   OpenObserve `logs` com o `productId` do token. O mesmo para traces no Redis
   Stream `lhamalog:traces`.
2. Nome de produto dentro do lote não troca o `productId`.
3. Lote vazio bem formado responde sucesso e não escreve no destino.
4. Sem token, token desconhecido, revogado ou expirado: não autorizado, e o
   destino não recebe o lote.
5. Corpo ilegível e lote em que todos os registros são inválidos: rejeição, sem
   escrita.
6. Lote misto: só os válidos no destino, com contagem de rejeitados na resposta.
7. OpenObserve parado no teste de logs, ou Redis parado no teste de traces:
   erro de destino, sem confirmação de sucesso.
8. HTTP e gRPC devolvem a mesma classe de resultado na mesma situação.

Os testes sobem MongoDB, OpenObserve e Redis por Testcontainers. O token do
teste é aleatório e só existe na memória daquele processo, já em forma de hash
no Mongo de teste.

Build completo, como pede a constituição antes do merge:

```powershell
.\mvnw.cmd clean verify
```

## Ensaio manual no perfil local

Sobe a infra (Mongo, Redis, OpenObserve e o collector de observabilidade):

```powershell
docker compose -f infra/local/compose.yaml up -d
```

Escolha um token só na sua sessão e calcule o SHA-256 em hex. Insira na coleção
`products` do banco `lhamalog_collector` um documento com esse `tokenHash`,
`status` `ACTIVE` e um `productId` seu. Não copie o token para o repositório.

Suba a aplicação:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

No perfil local o receptor HTTP fica na porta 4328 e o gRPC na 4327. As portas
4317 e 4318 continuam com o OpenTelemetry Collector do compose.

Envie um `POST` para `http://localhost:4328/v1/logs` com `Content-Type:
application/json` e `Authorization: Bearer` mais o token da sessão. O resultado
esperado para um lote válido é HTTP 200 e um registro novo no stream
OpenObserve `logs` com o seu `productId`. Repita a falha sem o header: HTTP 401
e nenhum registro novo.

Para o destino indisponível, pare o OpenObserve e repita o POST válido.
O esperado é HTTP 503.

## Fora deste ensaio

Métricas, cadastro de produto pela aplicação, busca no OpenObserve e
consumidor do stream de traces.
