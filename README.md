# lhamalog-collector

Ingestão OTLP (/v1/logs e /v1/traces) com token Bearer por produto; grava logs no Elasticsearch e publica eventos no Redis Streams. Serviço público.

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
| `REDIS_USERNAME` / `REDIS_PASSWORD` | não | vazio |
| `REDIS_SSL_ENABLED` | não | `false` |
| `ELASTICSEARCH_URIS` | sim | `https://host:9200` |
| `ELASTICSEARCH_USERNAME` / `ELASTICSEARCH_PASSWORD` | não | vazio |
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
├── compose.yaml                    # MongoDB, Redis, Elasticsearch e OpenTelemetry Collector
├── .env.example                    # portas e credenciais de desenvolvimento (copie para .env, opcional)
├── mongo-init/
│   └── 01-create-app-user.js       # cria o banco lhamalog_collector e o usuário da aplicação
└── otel-collector/
    └── config.yaml                 # recebe OTLP (4317 gRPC / 4318 HTTP) e imprime no log
```

| Serviço | Imagem (versão fixa) | Porta |
|---|---|---|
| MongoDB | `mongo:8.0.34` | 27017 |
| Redis | `redis:8.2.10` | 6379 |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.4.5` | 9200 |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib:0.163.1` | 4317, 4318, 13133 |

Subir, conferir e rodar (PowerShell, na raiz do repositório; precisa do Docker Desktop aberto):

```powershell
Copy-Item infra\local\.env.example infra\local\.env   # opcional: só se quiser mudar portas/senhas
make infra-up                                           # ou: docker compose -f infra/local/compose.yaml up -d
make infra-ps                                           # aguarde os serviços ficarem "healthy"
make run-local                                          # ou: .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

- Saúde da aplicação: `http://localhost:8080/actuator/health` (no perfil local mostra o estado de Mongo, Redis e Elasticsearch).
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
