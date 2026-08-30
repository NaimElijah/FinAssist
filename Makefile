# Single definition of setup, run and test. CI invokes these same targets, so a change here
# changes the pipeline too and the two cannot drift (docs/ADRs/ADR-005).
.DEFAULT_GOAL := help
SHELL := /bin/sh

LEDGER    := backend/ledger
ASSISTANT := backend/assistant
WEB       := frontend/web
MVNW      := ./mvnw -B
UV        := uv

.PHONY: help setup lock run stop \
        test test-ledger test-assistant test-web \
        lint lint-ledger lint-assistant lint-web \
        build-web format audit audit-assistant audit-web clean

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s%s", $$1, $$2, ORS}'

setup: ## Clean clone to ready: env file plus locked dependencies for every service
	@test -f .env || cp .env.example .env
	cd $(LEDGER) && $(MVNW) -q dependency:go-offline
	cd $(ASSISTANT) && $(UV) sync --locked --extra dev
	cd $(WEB) && npm ci

lock: ## Regenerate backend/assistant/uv.lock after editing pyproject.toml
	cd $(ASSISTANT) && $(UV) lock

run: ## Start the stack
	docker compose up --build

stop: ## Stop the stack, keeping volumes
	docker compose down

test: test-ledger test-assistant test-web ## Every test, with coverage. CI also gates lint, build and audit

test-ledger:
	cd $(LEDGER) && $(MVNW) verify

test-assistant:
	cd $(ASSISTANT) && $(UV) run pytest --cov --cov-report=term-missing --cov-report=xml

test-web:
	cd $(WEB) && npm run test:coverage

mutation-ledger: ## Mutation testing over the ledger domain; a diagnostic, gated on nothing (ADR-017)
	cd $(LEDGER) && $(MVNW) -Pmutation org.pitest:pitest-maven:mutationCoverage

lint: lint-ledger lint-assistant lint-web ## Format check and static analysis; never rewrites a file

lint-ledger:
	cd $(LEDGER) && $(MVNW) spotless:check

lint-assistant:
	cd $(ASSISTANT) && $(UV) run ruff check .
	cd $(ASSISTANT) && $(UV) run ruff format --check .
	cd $(ASSISTANT) && $(UV) run mypy src tests

lint-web:
	cd $(WEB) && npm run lint
	cd $(WEB) && npm run format:check
	cd $(WEB) && npm run typecheck

build-web: ## Production bundle
	cd $(WEB) && npm run build

format: ## Rewrite sources to match the formatters
	cd $(LEDGER) && $(MVNW) spotless:apply
	cd $(ASSISTANT) && $(UV) run ruff format .
	cd $(ASSISTANT) && $(UV) run ruff check --fix .
	cd $(WEB) && npm run format

audit: audit-assistant audit-web ## Fail on a known vulnerability in a pinned dependency

audit-assistant:
	cd $(ASSISTANT) && $(UV) run pip-audit

audit-web:
	cd $(WEB) && npm audit --audit-level=high

clean: ## Remove build output; leaves .env and Docker volumes alone
	cd $(LEDGER) && $(MVNW) -q clean
	rm -rf $(ASSISTANT)/.pytest_cache $(ASSISTANT)/.mypy_cache $(ASSISTANT)/.ruff_cache
	rm -f $(ASSISTANT)/coverage.xml $(ASSISTANT)/.coverage
	rm -rf $(WEB)/dist $(WEB)/coverage
