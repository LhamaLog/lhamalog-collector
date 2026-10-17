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
.\mvnw.cmd clean package
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

## Comandos

| Ação | Maven | Makefile |
|---|---|---|
| Rodar | `./mvnw spring-boot:run` | `make run` |
| Buildar | `./mvnw clean package` | `make build` |
| Testar | `./mvnw test` | `make test` |
| Verificação completa | `./mvnw clean verify` | `make verify` |

Executar o jar gerado:

```bash
java -jar target/lhamalog-collector-0.0.1-SNAPSHOT.jar
```

Perfil local: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

## Log de inicialização

Ao subir, o serviço registra em INFO uma linha padrão (ADR_149), emitida pela classe `config.StartupLogger` no evento `ApplicationReadyEvent`:

```
[LhamaLog] lhamalog-collector iniciado | versao=0.0.1-SNAPSHOT | porta=8080 | perfis=default | java=25
```

O nome vem de `spring.application.name`, a porta de `local.server.port`, a versão do `build-info` gerado pelo `spring-boot-maven-plugin` (ou `dev` se ausente) e os perfis ativos (ou `default`).

## Infra local
MongoDB, Redis e Elasticsearch locais pelo docker-compose da equipe (o mesmo do lhamalog-finder: `docker compose up -d` / `make infra-up`). Os testes usam Testcontainers e sobem os containers sozinhos.

## Dependências
Antes de adicionar qualquer biblioteca, ver Drive da equipe > Arquitetura > Dependências específicas dos módulos (evita bibliotecas com a mesma função).

Dependências a adicionar manualmente (fora do Initializr): io.opentelemetry.proto:opentelemetry-proto, io.grpc (grpc-netty-shaded, grpc-protobuf, grpc-services), com.github.ben-manes.caffeine:caffeine.

Sem GitHub Actions: rode `make verify` localmente antes de cada push.

## Padrão de commit

Mensagens no formato `CHAVE-JIRA: descricao` (ex.: `LLT-82: inicializacao do modulo: Collector`), conforme a ADR_148. Detalhes em [CONTRIBUTING.md](CONTRIBUTING.md).
