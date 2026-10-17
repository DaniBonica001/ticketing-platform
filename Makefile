# Local environment shortcuts. Run `make help` for the list.
# Optional overrides are read from .env (see .env.example).

-include .env
export

SHELL := /usr/bin/env bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

COMPOSE := docker compose
LAMBDA_CONTAINER_FILTER := name=^ticketing-localstack-lambda-
TOKEN_SUB_USER ?= user-1
TOKEN_SUB_ADMIN ?= admin-1
TOKEN_TTL ?= 900

.PHONY: help keys up down logs ps token-user token-admin bootstrap smoke demo load-test postman config clean-lambdas

help: ## Show available targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

keys: ## Generate the dev JWT key pair in local/secrets/ (idempotent)
	@scripts/generate-dev-keys.sh

up: keys ## Build and start the whole stack (LocalStack, bootstrap, OTel, API, processor)
	$(COMPOSE) up -d --build
	@echo "API:     http://127.0.0.1:8090"
	@echo "Grafana: http://127.0.0.1:3300 (dashboard 'Ticketing')"

down: ## Stop the stack and delete its volumes and Lambda containers
	$(COMPOSE) down -v --remove-orphans
	@$(MAKE) --no-print-directory clean-lambdas

clean-lambdas: ## Remove Lambda containers spawned by this stack's LocalStack (if any are left)
	@ids="$$(docker ps -aq --filter '$(LAMBDA_CONTAINER_FILTER)')"; \
	if [[ -n "$$ids" ]]; then docker rm -f $$ids >/dev/null && echo "Removed Lambda containers."; fi

logs: ## Follow logs of all services (SERVICE=name to filter)
	$(COMPOSE) logs -f --tail=200 $(SERVICE)

ps: ## Show service status
	$(COMPOSE) ps -a

token-user: ## Print a 15 min JWT with scope ticketing:user (TOKEN_SUB_USER=user-1)
	@scripts/issue-token.sh "$(TOKEN_SUB_USER)" user "$(TOKEN_TTL)"

token-admin: ## Print a 15 min JWT with scopes ticketing:admin ticketing:user (TOKEN_SUB_ADMIN=admin-1)
	@scripts/issue-token.sh "$(TOKEN_SUB_ADMIN)" admin "$(TOKEN_TTL)"

bootstrap: ## Re-run the idempotent AWS bootstrap against the running LocalStack
	$(COMPOSE) run --rm --no-deps aws-bootstrap

smoke: ## Health checks plus a listing of the AWS resources in LocalStack (127.0.0.1:4766)
	@scripts/smoke.sh

demo: ## Run the end-to-end purchase flows against the running stack (asserts every step)
	@scripts/demo-flow.sh

load-test: ## Concurrent purchase burst against the running stack; proves zero overselling
	@scripts/load-test.py

postman: ## Run the Postman collection with Newman (needs Node.js) using fresh dev tokens
	@npx -y newman@6.2.1 run postman/ticketing-api.postman_collection.json \
		-e postman/local.postman_environment.json \
		--env-var "userToken=$$(scripts/issue-token.sh postman-user user)" \
		--env-var "adminToken=$$(scripts/issue-token.sh postman-admin admin)" \
		--delay-request 1200 --timeout-request 15000

config: ## Validate docker-compose.yml
	$(COMPOSE) config -q && echo "docker-compose.yml is valid"
