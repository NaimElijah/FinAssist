# Single definition of setup, run and test. CI invokes these same targets, so a change here
# changes the pipeline too and the two cannot drift (docs/ADRs/ADR-005).
.DEFAULT_GOAL := help
SHELL := /bin/sh

LEDGER    := backend/ledger
GATEWAY   := backend/gateway
ASSISTANT := backend/assistant
WEB       := frontend/web
# ops/smoke as seen from $(ASSISTANT). It has no toolchain of its own, so lint-smoke and format
# borrow the assistant's pinned ruff and mypy and the rules in its pyproject.toml.
SMOKE     := ../../ops/smoke
# ops/backup as seen from $(ASSISTANT), linted the same way. Not named BACKUP, which
# `make restore BACKUP=<folder>` uses for the folder to restore.
BACKUP_TOOL := ../../ops/backup
# ops/secret_check as seen from $(ASSISTANT), linted and tested the same way (ADR-044).
SECRET_CHECK := ../../ops/secret_check
# ops/compose_check as seen from $(ASSISTANT), linted and tested the same way (ADR-049).
COMPOSE_CHECK := ../../ops/compose_check
# Compose over .env.example, never over .env: on a developer machine that holds the owner's
# secrets, and the resolved file would carry them. Compose ranks the shell's environment above any
# --env-file, and the native run exports real secrets there, so placeholders.env is exported into
# the recipe's own shell instead: each variable docker-compose.yml requires through `:?` resolves
# to its placeholder whatever the caller exported. Any other variable it interpolates, a port, a
# username or a flag, still takes the caller's exported value over .env.example's.
COMPOSE_CONFIG := set -a && . ./ops/compose_check/placeholders.env && set +a && docker compose --env-file .env.example
MVNW      := ./mvnw -B
UV        := uv

.PHONY: help setup lock run run-monitoring run-identity smoke-auth stop stop-monitoring backup restore restore-check \
        test test-ledger test-gateway test-assistant test-backup test-secret-check \
        test-compose-check test-web \
        mutation-ledger lint lint-ledger lint-gateway lint-assistant lint-smoke lint-backup \
        lint-secret-check lint-compose-check lint-web \
        build-web generate-web-api check-web-api check-compose format audit audit-assistant \
        audit-web clean

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s%s", $$1, $$2, ORS}'

setup: ## Clean clone to ready: env file plus locked dependencies for every service
	@test -f .env || cp .env.example .env
	cd $(LEDGER) && $(MVNW) -q dependency:go-offline
	cd $(GATEWAY) && $(MVNW) -q dependency:go-offline
	cd $(ASSISTANT) && $(UV) sync --locked --extra dev
	cd $(WEB) && npm ci

lock: ## Regenerate backend/assistant/uv.lock after editing pyproject.toml
	cd $(ASSISTANT) && $(UV) lock

run: ## Start the stack
	docker compose up --build

run-monitoring: ## Start the stack plus the collector, Prometheus and Grafana
	docker compose --profile monitoring up --build

run-identity: ## Start only Keycloak and its database, for signing in or testing without the rest
	docker compose up -d keycloak-db keycloak

# Run cold, `compose run` starts the services the check depends on and waits for them, but from
# whatever images already exist; `make run` is what builds them.
smoke-auth: ## Opt-in end-to-end check of the authentication and RBAC wiring (ops/smoke/auth_smoke.py); run after make run reports healthy
	docker compose --profile smoke run --rm auth-smoke

# Both databases must already be running; this never starts a container in your project.
backup: ## Dump the ledger's and Keycloak's databases into backups/<UTC time>/, or $BACKUP_DIR (ADR-038)
	$(UV) run ops/backup/backup.py backup

# BACKUP=<folder> is required. DATABASE=ledger|keycloak restores that database alone.
restore: ## Restore a backup into empty databases, ledger and keycloak stopped: make restore BACKUP=backups/<time>
	$(UV) run ops/backup/backup.py restore $(BACKUP) $(if $(DATABASE),--database $(DATABASE))

# Only ever in the Compose project finassist-restore-check, on ports of its own; never yours.
restore-check: ## Prove a backup restores and the stack comes back on it: make restore-check BACKUP=backups/<time>
	$(UV) run ops/backup/backup.py check $(BACKUP)

stop: ## Stop the stack, keeping volumes
	docker compose down

stop-monitoring: ## Stop the monitoring profile too, keeping volumes
	docker compose --profile monitoring down

test: test-ledger test-gateway test-assistant test-backup test-secret-check test-compose-check test-web ## Every test, with coverage. CI also gates lint, build and audit

test-ledger:
	cd $(LEDGER) && $(MVNW) verify

test-gateway:
	cd $(GATEWAY) && $(MVNW) verify

test-assistant:
	cd $(ASSISTANT) && $(UV) run pytest --cov --cov-report=term-missing --cov-report=xml

# The backup CLI's Docker-free logic, under the assistant's pytest settings (ADR-038).
test-backup:
	cd $(ASSISTANT) && $(UV) run pytest -c pyproject.toml $(BACKUP_TOOL)/tests

# The secret check, under the same settings: real git, no Docker (ADR-044).
test-secret-check:
	cd $(ASSISTANT) && $(UV) run pytest -c pyproject.toml $(SECRET_CHECK)/tests

# The Compose conventions check, on crafted resolved files: no Docker (ADR-049).
test-compose-check:
	cd $(ASSISTANT) && $(UV) run pytest -c pyproject.toml $(COMPOSE_CHECK)/tests

test-web:
	cd $(WEB) && npm run test:coverage

mutation-ledger: ## Mutation testing over the ledger domain; a diagnostic, gated on nothing (ADR-017)
	cd $(LEDGER) && $(MVNW) -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage

lint: lint-ledger lint-gateway lint-assistant lint-smoke lint-backup lint-secret-check lint-compose-check lint-web ## Format check and static analysis; never rewrites a file

lint-ledger:
	cd $(LEDGER) && $(MVNW) spotless:check

lint-gateway:
	cd $(GATEWAY) && $(MVNW) spotless:check

lint-assistant:
	cd $(ASSISTANT) && $(UV) run ruff check .
	cd $(ASSISTANT) && $(UV) run ruff format --check .
	cd $(ASSISTANT) && $(UV) run mypy src tests

lint-smoke:
	cd $(ASSISTANT) && $(UV) run ruff check $(SMOKE)
	cd $(ASSISTANT) && $(UV) run ruff format --check $(SMOKE)
	cd $(ASSISTANT) && $(UV) run mypy --strict $(SMOKE)

lint-backup:
	cd $(ASSISTANT) && $(UV) run ruff check $(BACKUP_TOOL)
	cd $(ASSISTANT) && $(UV) run ruff format --check $(BACKUP_TOOL)
	cd $(ASSISTANT) && $(UV) run mypy --strict $(BACKUP_TOOL)

lint-secret-check:
	cd $(ASSISTANT) && $(UV) run ruff check $(SECRET_CHECK)
	cd $(ASSISTANT) && $(UV) run ruff format --check $(SECRET_CHECK)
	cd $(ASSISTANT) && $(UV) run mypy --strict $(SECRET_CHECK)

lint-compose-check:
	cd $(ASSISTANT) && $(UV) run ruff check $(COMPOSE_CHECK)
	cd $(ASSISTANT) && $(UV) run ruff format --check $(COMPOSE_CHECK)
	cd $(ASSISTANT) && $(UV) run mypy --strict $(COMPOSE_CHECK)

lint-web:
	cd $(WEB) && npm run lint
	cd $(WEB) && npm run format:check
	cd $(WEB) && npm run typecheck

build-web: ## Production bundle
	cd $(WEB) && npm run build

generate-web-api: ## Regenerate the web client's contract types from contracts/*.openapi.json
	cd $(WEB) && npm run generate:api

# Not part of `make lint`, which never rewrites a file: openapi-ts.config.ts fixes the output paths,
# so this regenerates the types in place, and in a dirty tree reports uncommitted ones as drift.
# `git diff` compares tracked files only, so a file the generator newly emits would pass it while
# the committed types stay incomplete. `git status --porcelain` lists untracked files too, so either
# kind of drift fails the check, and the listing names the files (ADR-049).
check-web-api: ## Fail if regenerating the web client's contract types changes any file; rewrites them in place
	cd $(WEB) && npm run generate:api
	cd $(WEB) && git status --porcelain -- src/shared/api/contracts
	cd $(WEB) && test -z "$$(git status --porcelain -- src/shared/api/contracts)"

# --profile monitoring, or the three monitoring services are interpolated but never validated, and a
# broken reference among them passes. --profile smoke does the same for ops/smoke/auth_smoke.py's
# auth-smoke service (Refs #38); running that check stays manual (`make smoke-auth`). `config`
# starts no container, so this never touches a Compose project (ADR-049). The script's PEP 723
# `requires-python` is a minimum, so `--python` pins the assistant's .python-version: otherwise uv
# may pick, or on CI download, a newer CPython than the one lint-compose-check's mypy targets.
check-compose: ## Validate docker-compose.yml under every profile, then its restart, log, readiness, port, profile, hardening, host-privilege, network, CORS and Spring-environment conventions; never reads .env, and every `:?` secret is a placeholder even if exported
	$(COMPOSE_CONFIG) --profile monitoring config --quiet
	$(COMPOSE_CONFIG) --profile smoke config --quiet
	$(COMPOSE_CONFIG) --profile monitoring --profile smoke config --quiet
	$(COMPOSE_CONFIG) --profile monitoring --profile smoke config --format json \
		| $(UV) run --python "$$(cat $(ASSISTANT)/.python-version)" ops/compose_check/compose_check.py

format: ## Rewrite sources to match the formatters
	cd $(LEDGER) && $(MVNW) spotless:apply
	cd $(GATEWAY) && $(MVNW) spotless:apply
	cd $(ASSISTANT) && $(UV) run ruff format . $(SMOKE) $(BACKUP_TOOL) $(SECRET_CHECK) $(COMPOSE_CHECK)
	cd $(ASSISTANT) && $(UV) run ruff check --fix . $(SMOKE) $(BACKUP_TOOL) $(SECRET_CHECK) $(COMPOSE_CHECK)
	cd $(WEB) && npm run format

audit: audit-assistant audit-web ## Fail on a known vulnerability in a pinned dependency

audit-assistant:
	cd $(ASSISTANT) && $(UV) run pip-audit

audit-web:
	cd $(WEB) && npm audit --audit-level=high

clean: ## Remove build output; leaves .env and Docker volumes alone
	cd $(LEDGER) && $(MVNW) -q clean
	cd $(GATEWAY) && $(MVNW) -q clean
	rm -rf $(ASSISTANT)/.pytest_cache $(ASSISTANT)/.mypy_cache $(ASSISTANT)/.ruff_cache
	rm -f $(ASSISTANT)/coverage.xml $(ASSISTANT)/.coverage
	rm -rf $(WEB)/dist $(WEB)/coverage
