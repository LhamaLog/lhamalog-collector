# lhamalog-collector

Ingestão OTLP (/v1/logs e /v1/traces, HTTP e gRPC) com token Bearer por produto; grava logs e traces no OpenObserve (ADR_168). Serviço público.

Parte da plataforma LhamaLog (SaaS multi-tenant de análise de logs com IA). Pacote base `br.com.lhamalog.collector`, groupId `br.com.lhamalog`. Gerado no Spring Initializr: Java 25, Spring Boot 4.1.1, Maven. Estrutura proposta: hexagonal leve.

## Pré-requisitos
- JDK 25 (Amazon Corretto 25 recomendado) — ver ADR_150
- Docker (para a infra local e os testes com Testcontainers)
- make (opcional; Linux/macOS ou WSL)
- Maven não é necessário: use o wrapper `./mvnw`

## Instalando o Java 25

Os módulos Java exigem **JDK 25** (ADR_150). A distribuição recomendada é o **Amazon Corretto 25**, mas qualquer JDK 25 funciona (ex.: Temurin).

**Windows (PowerShell):**

```powershell
winget install Amazon.Corretto.25.JDK
```

Definir o `JAVA_HOME` (usuário) apontando para o JDK 25 instalado:

```powershell
$jdk = Get-ChildItem "C:\Program Files\Amazon Corretto" -Directory |
  Where-Object Name -like "jdk25*" | Sort-Object Name | Select-Object -Last 1
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk.FullName, "User")
```

Se `java -version` ainda mostrar outra versão (ex.: 17), coloque `%JAVA_HOME%\bin` no **topo** do `Path` (Configurações > Variáveis de ambiente) e remova/rebaixe entradas de JDKs antigos.

Feche e abra o terminal e confira:

```powershell
java -version      # deve mostrar 25
.\mvnw.cmd -v      # "Java version: 25..."
```

**Linux:**

```bash
# Debian/Ubuntu (repositório apt oficial da Corretto)
wget -O - https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && \
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | sudo tee /etc/apt/sources.list.d/corretto.list
sudo apt-get update && sudo apt-get install -y java-25-amazon-corretto-jdk

# Fedora/RHEL (repositório rpm oficial da Corretto)
sudo rpm --import https://yum.corretto.aws/corretto.key
sudo curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
sudo dnf install -y java-25-amazon-corretto-devel

# Alternativa: SDKMAN (o identificador muda a cada release; confira com `sdk list java`)
sdk list java | grep amzn
sdk install java 25.0.1.0+8-amzn   # troque pelo identificador 25 listado acima
```

Defina o `JAVA_HOME` no `~/.bashrc` (o SDKMAN já faz isso sozinho):

```bash
echo 'export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))' >> ~/.bashrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
java -version
```

Se aparecer outra versão: `sudo update-alternatives --config java` (Debian/Ubuntu) ou `sudo alternatives --config java` (Fedora/RHEL).

> **Erro comum:** `release version 25 not supported` significa que o Maven está usando um JDK antigo (17/21). Instale o JDK 25, ajuste o `JAVA_HOME`, reabra o terminal e confira com `.\mvnw.cmd -v` / `./mvnw -v`.

## Instalando o make

Os comandos `make` abaixo precisam do GNU Make instalado.

**Windows (PowerShell):**

```powershell
winget install ezwinports.make
# alternativa: choco install make   (ou use Git Bash / WSL)
```

Depois feche e abra o terminal e confira com `make --version`.

**Linux:**

```bash
# Debian/Ubuntu
sudo apt update && sudo apt install -y make
# Fedora
sudo dnf install -y make
# Arch
sudo pacman -S make
make --version
```

macOS (opcional): `xcode-select --install`.

**Sem make no Windows:** use os equivalentes diretos:

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
.\mvnw.cmd clean package
.\mvnw.cmd test
.\mvnw.cmd clean verify
docker compose -f infra/local/compose.yaml up -d
docker compose -f infra/local/compose.yaml down
```

> No PowerShell, argumentos `-D...` com ponto precisam de aspas (`"-Dspring-boot.run.profiles=local"`); sem aspas o PowerShell quebra o argumento no ponto.

## Comandos

| Ação | Sem make | Makefile |
|---|---|---|
| Rodar (perfil padrão: `local`) | `./mvnw spring-boot:run` | `make run` |
| Rodar com o perfil local | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` | `make run-local` |
| Buildar | `./mvnw clean package` | `make build` |
| Testar | `./mvnw test` | `make test` |
| Verificação completa | `./mvnw clean verify` | `make verify` |
| Subir o ambiente local | `docker compose -f infra/local/compose.yaml up -d` | `make infra-up` |
| Parar o ambiente local | `docker compose -f infra/local/compose.yaml down` | `make infra-down` |
| Status dos containers | `docker compose -f infra/local/compose.yaml ps` | `make infra-ps` |
| Logs dos containers | `docker compose -f infra/local/compose.yaml logs -f` | `make infra-logs` |
| Validar o compose | `docker compose -f infra/local/compose.yaml config -q` | `make infra-config` |

No Windows, troque `./mvnw` por `.\mvnw.cmd` (o Makefile já faz isso sozinho).

Executar o jar gerado:

```bash
java -jar target/lhamalog-collector-0.0.1-SNAPSHOT.jar                              # perfil local (padrão)
java -jar target/lhamalog-collector-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod # produção (exige as variáveis abaixo)
```

## Receptor OTLP (LLT-181)

O collector recebe logs e traces OTLP de cada produto e grava no **OpenObserve**, organização `lhamalog`, streams `logs` e `traces` (ADR_168). Métricas OTLP não são aceitas (HTTP 404, gRPC `UNIMPLEMENTED`).

| Canal | Caminho / serviço | Porta local | Porta prod |
|---|---|---|---|
| OTLP/HTTP | `POST /v1/logs`, `POST /v1/traces` (`application/x-protobuf` ou `application/json`, gzip opcional) | 4328 | 4318 |
| OTLP/gRPC | `LogsService/Export`, `TraceService/Export` | 4327 | 4317 |

No local as portas são 4328/4327 porque 4318/4317 já são do OpenTelemetry Collector do compose. Sobrescreva com `OTLP_RECEIVER_HTTP_PORT` / `OTLP_RECEIVER_GRPC_PORT`; desligue um canal com `OTLP_RECEIVER_HTTP_ENABLED=false` / `OTLP_RECEIVER_GRPC_ENABLED=false`. Limite de mensagem: `OTLP_RECEIVER_MAX_MESSAGE_SIZE` (64MB, medido depois de descomprimir).

**Autenticação:** header `Authorization: Bearer <token>` (no gRPC, metadata `authorization`). O collector calcula o SHA-256 (hex minúsculo) do token e procura em `tokenHash` na coleção MongoDB `product_tokens` (`productId`, `tokenHash`, `status` `ACTIVE`/`REVOKED`, `createdAt`, `revokedAt`, `expiresAt`). O produto é lido em `products` (campo `productId`), e o `tenantId` dele é gravado junto. Cada log/span no OpenObserve leva `tenantId` e `productId` do token; o conteúdo do lote não troca esses campos. O collector só lê essas coleções; cadastro, rotação e revogação de token ficam fora dele. Tokens válidos ficam em cache por 30 s (uma revogação vale em até 30 s); tokens recusados ficam num cache negativo menor, de 3 s e até 1000 entradas, para um token errado repetido não bater no MongoDB a cada requisição. Falha do MongoDB nunca entra em cache. Ajuste com `OTLP_RECEIVER_TOKEN_CACHE_TTL`, `OTLP_RECEIVER_TOKEN_CACHE_MAX_SIZE`, `OTLP_RECEIVER_NEGATIVE_TOKEN_CACHE_TTL` e `OTLP_RECEIVER_NEGATIVE_TOKEN_CACHE_MAX_SIZE`.

**Respostas:** 200 (com `partial_success` quando parte dos registros é inválida ou recusada), 400 (corpo ilegível, todos inválidos ou todos recusados pelo OpenObserve como dado inválido), 401 + `WWW-Authenticate: Bearer` (a autenticação vem antes de 413/415), 413 (acima do limite), 415 (encoding/content type não suportado), 503 + `Retry-After` (MongoDB ou OpenObserve fora, timeout, 5xx ou servidor HTTP no limite de requisições simultâneas). Contrato completo em `specs/001-otlp-receiver/contracts/otlp-export.md`.

**Janela de horário:** o OpenObserve só aceita `_timestamp` de até 5 h atrás (`ZO_INGEST_ALLOWED_UPTO`). O collector recusa antes, e conta em `partial_success`, log/span mais antigo que 4h50 (`OTLP_RECEIVER_TIMESTAMP_MAX_AGE`, padrão `290m`) ou mais de 1 h no futuro (`OTLP_RECEIVER_TIMESTAMP_MAX_FUTURE`). O horário do log é `timeUnixNano`; se vier 0, `observedTimeUnixNano`; se os dois vierem 0, a hora de recebimento (sem checar janela). Span usa `startTimeUnixNano`. Se mesmo assim o OpenObserve recusar dados (HTTP 400/413/415/422 ou `failed` > 0 na resposta), isso não é repetível: vira 400 ou `partial_success`, nunca 503.

**Campos gravados:** além de `tenantId`, `productId` e dos atributos do registro, cada documento leva o contexto OTLP: `serviceName` (atributo `service.name` do resource), `resourceAttributes` (JSON), `scopeName`, `scopeVersion`; logs também `severityText`, `observedTimeUnixNano` e `flags`; spans também `traceState`, `kind`, `statusCode`, `statusMessage`, `events` e `links` (JSON). O OpenObserve grava nomes de campo em minúsculas (`servicename`, `resourceattributes`, `scopename`, ...): use esses nomes nas buscas SQL. Mapeamento completo em `specs/001-otlp-receiver/data-model.md`.

**Servidor HTTP:** o OTLP/HTTP usa o servidor HTTP do JDK, com limites: até 200 requisições simultâneas (`OTLP_RECEIVER_HTTP_MAX_CONCURRENT_REQUESTS`; acima disso, 503 + `Retry-After: 1`), 1000 conexões (`OTLP_RECEIVER_HTTP_MAX_CONNECTIONS`), 30 s para ler a requisição e escrever a resposta (`OTLP_RECEIVER_HTTP_READ_TIMEOUT` / `OTLP_RECEIVER_HTTP_WRITE_TIMEOUT`), 30 s de conexão ociosa (`OTLP_RECEIVER_HTTP_IDLE_TIMEOUT`) e 200 conexões ociosas (`OTLP_RECEIVER_HTTP_MAX_IDLE_CONNECTIONS`). Esses tempos viram propriedades `sun.net.httpserver.*`, que valem para a JVM inteira; um `-Dsun.net.httpserver...` passado na linha de comando tem prioridade. Em produção, **coloque um proxy reverso** (nginx, Traefik ou o balanceador da nuvem) na frente da porta 4318, com TLS, limite de corpo e de taxa; o servidor do JDK não deve ficar exposto direto na internet. `OTLP_RECEIVER_MAX_MESSAGE_SIZE` precisa ser maior que 0 e no máximo 2147483647 bytes, senão o serviço não sobe.

**Redis Stream (LLT-182):** depois que o lote passa pela autenticação, pela validação e é gravado no OpenObserve, o collector publica **uma referência por lote** (não o conteúdo dos logs) para a Varredura: logs em `lhamalog:stream:logs` (evento `log.ingested`, `schemaVersion` 1) e traces em `lhamalog:stream:traces` (evento `trace.ingested`, `schemaVersion` 1), com `XADD` e `MAXLEN ~` (padrão 100000 via `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` e `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN`). Os atributos OTel ficam no OpenObserve; cada documento leva `lhamalog_batch_id` e `lhamalog_record_id`, e a referência traz organização, stream, `batchId` e a faixa de `_timestamp` para o Finder carregar o lote. O `tenantId` vai só nos metadados do envelope; prioridade e criticidade vêm só da severidade. Lote sem token válido, inválido ou vazio nunca chega ao stream. O `idempotencyId` é o `batchId` do lote, determinístico: o reenvio do mesmo lote repete o id e a Varredura deduplica por lote. Contrato em `specs/002-tenant-product-logs/contracts/log-ingested-stream.md`. No ambiente local o Redis exige senha: defina `REDIS_PASSWORD` em `infra/local/.env`.

## Retry e DLQ (LLT-184)

O collector faz até 3 tentativas totais para publicar cada referência no Redis Stream, com esperas de 100 e
200 ms entre elas (a espera de 400 ms só ocorreria com 4 tentativas). Depois disso, grava o envelope original
na DLQ do domínio: `lhamalog:stream:logs:dlq` ou `lhamalog:stream:traces:dlq`. A DLQ inclui `targetStream`,
`attempts`, `lastError` sanitizado e `failedAt`; o formato dos eventos `log.ingested` e `trace.ingested`
permanece no `schemaVersion` 1 e continua sendo uma referência por lote.

Se a DLQ confirmar, a resposta ao exportador é sucesso. A resposta é 503 + `Retry-After: 5` (gRPC
`UNAVAILABLE`) somente quando a DLQ também falha. Como principal e DLQ usam a mesma instância Redis, Redis
totalmente indisponível também impede a DLQ: nesse caso a resposta é 503 e a durabilidade depende do reenvio
do cliente.

## Log de inicialização

Ao subir, o serviço registra em INFO uma linha padrão (ADR_149), emitida pela classe `config.StartupLogger` no evento `ApplicationReadyEvent`:

```
[LhamaLog] lhamalog-collector iniciado | versao=0.0.1-SNAPSHOT | porta=8080 | perfis=local | java=25
```

O nome vem de `spring.application.name`, a porta de `local.server.port`, a versão do `build-info` gerado pelo `spring-boot-maven-plugin` (ou `dev` se ausente) e os perfis ativos; sem perfil ativo explícito, mostra o perfil padrão (`spring.profiles.default`, hoje `local`).

## Perfis de execução (ADR_151)

| Perfil | Quando usar | De onde vêm as configurações |
|---|---|---|
| `local` (**padrão**) | Na sua máquina, contra os containers de `infra/local/` | `application-local.properties`, com valores de desenvolvimento (sobrescrevíveis por variável de ambiente) |
| `prod` | Servidor/produção | `application-prod.properties`, que só lê variáveis de ambiente (nenhum host ou segredo no repositório) |

- `application.properties` guarda só a base comum: nome da aplicação, porta (`SERVER_PORT`, padrão 8080), actuator e o liga/desliga do OTLP.
- **Perfil padrão:** `spring.profiles.default=local`. Rodar sem informar perfil (`make run`, `.\mvnw.cmd spring-boot:run`, `java -jar ...`) usa o `local`, que não tem segredos reais. Produção sempre ativa `prod` de forma explícita (`SPRING_PROFILES_ACTIVE=prod`) e, se faltar uma variável obrigatória, o serviço **não sobe** (ex.: `Could not resolve placeholder 'MONGODB_URI'`), em vez de subir apontando para `localhost`.
- Escolher o perfil: `make run-local`, `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`, `$env:SPRING_PROFILES_ACTIVE="prod"` (PowerShell) ou `--spring.profiles.active=prod` no `java -jar`.

**Desligar a exportação OTLP** (rodar sem o OpenTelemetry Collector, sem erros de conexão na porta 4318):

```powershell
$env:OTLP_ENABLED="false"; make run-local
# sem make:
$env:OTLP_ENABLED="false"; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
# para voltar a exportar:
Remove-Item Env:OTLP_ENABLED
```

No Linux/macOS: `OTLP_ENABLED=false make run-local`.

**Variáveis do perfil `prod`:**

| Variável | Obrigatória | Exemplo / padrão |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sim | `prod` |
| `MONGODB_URI` | sim | `mongodb://usuario:senha@host:27017/lhamalog_collector?authSource=lhamalog_collector` |
| `REDIS_HOST` | sim | host do Redis |
| `REDIS_PORT` | não | `6379` |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | não (recomendado) | vazio |
| `REDIS_TIMEOUT` / `REDIS_CONNECT_TIMEOUT` | não | `2s` / `2s` |
| `LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN` | não | `100000` |
| `LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN` | não | `100000` |
| `LHAMALOG_MESSAGING_STREAM_LOGS_DLQ_MAX_LEN` | não | `100000` |
| `LHAMALOG_MESSAGING_STREAM_TRACES_DLQ_MAX_LEN` | não | `100000` |
| `LHAMALOG_MESSAGING_PUBLISH_MAX_ATTEMPTS` | não | `3` |
| `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_INITIAL_MS` | não | `100` |
| `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_MULTIPLIER` | não | `2` |
| `LHAMALOG_MESSAGING_PUBLISH_BACKOFF_CEILING_MS` | não | `2000` |
| `REDIS_SSL_ENABLED` | não | `false` |
| `OPENOBSERVE_URL` | sim | `https://openobserve:5080` |
| `OPENOBSERVE_USERNAME` / `OPENOBSERVE_PASSWORD` | sim | credencial de ingestão do OpenObserve |
| `OPENOBSERVE_ORGANIZATION` | não | `lhamalog` |
| `OPENOBSERVE_LOGS_STREAM` / `OPENOBSERVE_TRACES_STREAM` | não | `logs` / `traces` |
| `OTLP_RECEIVER_HTTP_PORT` / `OTLP_RECEIVER_GRPC_PORT` | não | `4318` / `4317` |
| `OTLP_RECEIVER_TIMESTAMP_MAX_AGE` / `OTLP_RECEIVER_TIMESTAMP_MAX_FUTURE` | não | `290m` / `1h` |
| `OTLP_RECEIVER_HTTP_MAX_CONCURRENT_REQUESTS` / `OTLP_RECEIVER_HTTP_MAX_CONNECTIONS` | não | `200` / `1000` |
| `OTLP_METRICS_URL` | sim | `http://otel-collector:4318/v1/metrics` |
| `OTLP_TRACES_URL` | sim | `http://otel-collector:4318/v1/traces` |
| `OTLP_ENABLED` | não | `true` |
| `TRACING_SAMPLING_PROBABILITY` | não | `0.1` |
| `SERVER_PORT` | não | `8080` |

`OTLP_METRICS_URL` e `OTLP_TRACES_URL` são exigidas mesmo com `OTLP_ENABLED=false`.

## Ambiente local (infra/local)

Tudo que o ambiente local usa fica em **uma pasta só**, `infra/local/`:

```
infra/local/
├── compose.yaml                    # MongoDB, Redis, OpenObserve e OpenTelemetry Collector
├── .env.example                    # portas e credenciais de desenvolvimento (copie para .env, obrigatório)
├── mongo-init/
│   └── 01-create-app-user.js       # cria o banco lhamalog_collector e o usuário da aplicação
└── otel-collector/
    └── config.yaml                 # recebe OTLP (4317 gRPC / 4318 HTTP) e imprime no log
```

| Serviço | Imagem (versão fixa) | Porta |
|---|---|---|
| MongoDB | `mongo:8.0.34` | 27017 |
| Redis | `redis:8.2.10` | 6379 |
| OpenObserve | `public.ecr.aws/zinclabs/openobserve:v1.1.2` | 5080 |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib:0.163.1` | 4317, 4318, 13133 |

Subir, conferir e rodar (PowerShell, na raiz do repositório; precisa do Docker Desktop aberto):

```powershell
Copy-Item infra\local\.env.example infra\local\.env   # preencha MONGO_ROOT_PASSWORD, MONGO_APP_PASSWORD, OPENOBSERVE_USERNAME (e-mail) e OPENOBSERVE_PASSWORD
make infra-up                                           # ou: docker compose -f infra/local/compose.yaml up -d
make infra-ps                                           # aguarde os serviços ficarem "healthy"
make run-local                                          # ou: .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

- O `.env` é obrigatório: `MONGO_ROOT_PASSWORD`, `MONGO_APP_PASSWORD`, `OPENOBSERVE_USERNAME` e `OPENOBSERVE_PASSWORD` não têm valor padrão, e o compose para com erro (`defina ... em infra/local/.env`) se faltarem. A aplicação local se conecta com `MONGODB_URI`; se a senha do usuário da aplicação não for a padrão de desenvolvimento, defina `$env:MONGODB_URI="mongodb://<usuario>:<senha>@localhost:27017/lhamalog_collector?authSource=lhamalog_collector"`. A senha do usuário da aplicação só é aplicada na primeira subida (volume vazio).
- Saúde da aplicação: `http://localhost:8080/actuator/health` (no perfil local mostra o estado de Mongo e Redis).
- OpenObserve: `http://localhost:5080`, com o usuário e a senha do seu `infra/local/.env`. A aplicação lê `OPENOBSERVE_USERNAME`/`OPENOBSERVE_PASSWORD` do ambiente.
- Métricas e traces enviados ao collector aparecem em `make infra-logs` (ou `docker compose -f infra/local/compose.yaml logs -f otel-collector`).
- Parar: `make infra-down` (mantém os dados). Para apagar os dados locais e rodar de novo o script do Mongo: `docker compose -f infra/local/compose.yaml down -v`.
- Se mudar portas ou credenciais no `.env`, ajuste também a aplicação por variável de ambiente (ex.: `$env:MONGODB_URI="..."`).
- O `infra/local/.env` está no `.gitignore`; só o `.env.example` vai para o repositório.
- As portas são as mesmas do docker-compose do lhamalog-finder: não suba os dois ao mesmo tempo (ou mude as portas no `.env`).

Os testes (`make test`) usam Testcontainers e sobem os próprios containers; não dependem de `infra/local/`.

## Dependências
Antes de adicionar qualquer biblioteca, ver Drive da equipe > Arquitetura > Dependências específicas dos módulos (evita bibliotecas com a mesma função).

Dependências a adicionar manualmente (fora do Initializr): io.opentelemetry.proto:opentelemetry-proto, io.grpc (grpc-netty-shaded, grpc-protobuf, grpc-services), com.github.ben-manes.caffeine:caffeine.

Sem GitHub Actions: rode `make verify` localmente antes de cada push.

## Padrão de commit

Mensagens no formato `CHAVE-JIRA: descricao` (ex.: `LLT-82: inicializacao do modulo: Collector`), conforme a ADR_148. Detalhes em [CONTRIBUTING.md](CONTRIBUTING.md).
