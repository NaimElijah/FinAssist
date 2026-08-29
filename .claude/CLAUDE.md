# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

Two service modules, both building and CI-gated, neither carrying domain code yet. The
ledger boots and answers Actuator probes; the assistant has no entry point. Read
`docs/requirements/requirements.md` and `docs/ADRs/` before proposing work — several
decisions are recorded but not yet ratified.

## Commands

Every target below is a thin wrapper defined in `Makefile`; CI invokes the same ones. `make`
is **not installed** on the development machine, so the raw command is given for each.

- Build / setup: `make setup` — `cd ledger && ./mvnw -B dependency:go-offline`, then
  `cd assistant && uv sync --locked --extra dev`
- Run: `make run` — `docker compose up --build` (starts PostgreSQL only; the ledger runs
  natively with `cd ledger && ./mvnw spring-boot:run`)
- Test (all): `make test`
- Test (ledger): `cd ledger && ./mvnw -B verify`
- Test (assistant): `cd assistant && uv run pytest`
- Test (single, ledger): `cd ledger && ./mvnw -B test -Dtest=LedgerArchitectureTest`
- Test (single, assistant): `cd assistant && uv run pytest tests/test_architecture.py -k <name>`
- Lint / types: `make lint` — `./mvnw -B spotless:check`; `uv run ruff check .`,
  `uv run ruff format --check .`, `uv run mypy src tests`
- Format: `make format` — `./mvnw -B spotless:apply`; `uv run ruff format .`
- Audit: `make audit` — `cd assistant && uv run pip-audit`
- Re-lock Python deps: `make lock` — `cd assistant && uv lock` (required after any
  `pyproject.toml` dependency edit, or CI's `uv sync --locked` fails)

Keep this list in sync with the Development table in `README.md`.

## Layout

- `ledger/` — Java 21, Spring Boot. Full tactical DDD: `domain/{account,transaction,shared,event}`,
  `application/{port/in,port/out,service}`, `adapter/{in/web,out/persistence}`.
- `assistant/` — Python 3.13, FastAPI. Hexagonal with a pure-function core and no aggregates:
  `domain/{analysis,model}`, `application/{port,usecase}`,
  `adapters/{inbound/api,outbound/ledger,outbound/llm}`.
- `contracts/` — the versioned OpenAPI contract between the services. Empty until the first endpoint.
- `docs/ADRs/` — one file per decision, `ADR-NNN-short-kebab-title.md`, zero-padded and
  monotonically increasing. Append-only: supersede, never rewrite.
- `docs/requirements/` — what the system must do. `docs/architecture/` — what it actually is.
- `.claude/` — project agent configuration; tracked in git. Only
  `.claude/settings.local.json` is ignored.

## Conventions

These are enforced by the build, not by review. Breaking one fails CI.

- **The architecture is asymmetric on purpose** (ADR-007). Tactical DDD — aggregates, value
  objects, domain events — belongs in `ledger/` only, where invariants exist. Do not add
  aggregates or repositories to `assistant/`; its core is pure functions over frozen
  dataclasses.
- **Dependencies point inward** in both services. `LedgerArchitectureTest` and
  `assistant/tests/test_architecture.py` enforce this.
- **The ledger domain imports no framework.** No `org.springframework..`, no `jakarta..`.
  Persistence entities live in `adapter/out/persistence` and are mapped — an entity is not
  an aggregate.
- **Money is never floating point.** `BigDecimal` in Java, `Decimal` in Python, `NUMERIC` in
  Postgres. A `double` or `float` field in the ledger domain fails the build.
- **Nothing inward may import `finassist_assistant.adapters`.** The anti-corruption layer in
  `adapters/outbound/ledger` is the only place a ledger DTO becomes a domain value; letting
  one leak inward couples the two services across a language boundary.
- **Test names are the specification:** `Given<scenario>_When<action>_Then<result>`. Ruff's
  `N802` is disabled for `tests/**` because of it.
- **No shared kernel across languages.** `Money` is implemented twice and pinned by
  equivalent tests on each side; do not try to share it.

`ledger/src/test/resources/archunit.properties` sets `archRule.failOnEmptyShould=false`
because the layer packages are still empty. **Delete that line with the first domain class**,
or the ledger's rules stay dormant.
