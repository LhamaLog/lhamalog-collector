# Wrapper do Maven: no Windows (cmd/PowerShell) usa mvnw.cmd; no Linux/macOS usa ./mvnw
ifeq ($(OS),Windows_NT)
MVNW := mvnw.cmd
else
MVNW := ./mvnw
endif

# Ambiente local (ADR_151): tudo em infra/local/
COMPOSE := docker compose -f infra/local/compose.yaml

.PHONY: run run-local build test verify clean infra-up infra-down infra-ps infra-logs infra-config

run:
	$(MVNW) spring-boot:run

# Roda com o perfil local (MongoDB, Redis, Elasticsearch e OTel Collector de infra/local)
run-local:
	$(MVNW) spring-boot:run -Dspring-boot.run.profiles=local

build:
	$(MVNW) clean package

test:
	$(MVNW) test

verify:
	$(MVNW) clean verify

clean:
	$(MVNW) clean

# Sobe o ambiente local em segundo plano
infra-up:
	$(COMPOSE) up -d

# Para e remove os containers (os volumes com os dados sao mantidos)
infra-down:
	$(COMPOSE) down

infra-ps:
	$(COMPOSE) ps

infra-logs:
	$(COMPOSE) logs -f

# Valida o compose.yaml (e o .env, se existir)
infra-config:
	$(COMPOSE) config -q
