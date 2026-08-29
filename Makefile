# Single definition of setup, run and test. CI invokes these same targets, so a change here
# changes the pipeline too and the two cannot drift (docs/ADRs/ADR-005).
.DEFAULT_GOAL := help
SHELL := /bin/sh

LEDGER    := backend/ledger
ASSISTANT := backend/assistant
MVNW      := ./mvnw -B
UV        := uv

.PHONY: help setup lock run stop \
        test test-ledger test-assistant \
        lint lint-ledger lint-assistant \
        format audit clean

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'

setup: ## Clean clone to ready: env file plus locked dependencies for both services
	@test -f .env || cp .env.example .env
	cd $(LEDGER) && $(MVNW) -q dependency:go-offline
	cd $(ASSISTANT) && $(UV) sync --locked --extra dev

lock: ## Regenerate backend/assistant/uv.lock after editing pyproject.toml
	cd $(ASSISTANT) && $(UV) lock

run: ## Start the stack
	docker compose up --build

stop: ## Stop the stack, keeping volumes
	docker compose down

test: test-ledger test-assistant ## Full suite - exactly what CI gates on

test-ledger:
	cd $(LEDGER) && $(MVNW) verify

test-assistant:
	cd $(ASSISTANT) && $(UV) run pytest

lint: lint-ledger lint-assistant ## Format check and static analysis; never rewrites a file

lint-ledger:
	cd $(LEDGER) && $(MVNW) spotless:check

lint-assistant:
	cd $(ASSISTANT) && $(UV) run ruff check .
	cd $(ASSISTANT) && $(UV) run ruff format --check .
	cd $(ASSISTANT) && $(UV) run mypy src tests

format: ## Rewrite sources to match the formatters
	cd $(LEDGER) && $(MVNW) spotless:apply
	cd $(ASSISTANT) && $(UV) run ruff format .
	cd $(ASSISTANT) && $(UV) run ruff check --fix .

audit: ## Fail on a known vulnerability in a pinned Python dependency
	cd $(ASSISTANT) && $(UV) run pip-audit

clean: ## Remove build output; leaves .env and Docker volumes alone
	cd $(LEDGER) && $(MVNW) -q clean
	rm -rf $(ASSISTANT)/.pytest_cache $(ASSISTANT)/.mypy_cache $(ASSISTANT)/.ruff_cache
