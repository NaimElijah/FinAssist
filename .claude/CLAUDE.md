# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

Three codebases — a Java ledger, a Python assistant, a React web client — all building and
CI-gated. **All five bounded contexts now have a domain layer**, with tests. Nothing above the
domain exists: no application services, no ports, no adapters, no persistence, no HTTP. The
ledger boots and answers Actuator probes; the assistant has no entry point; the web client
renders a placeholder.

Read `docs/requirements/requirements.md` and `docs/ADRs/` before proposing work. Several
decisions are recorded but not yet ratified, and the requirements are deliberately `TODO`
rather than inferred from code.

## Commands

Every target below is a thin wrapper defined in `Makefile`; CI invokes the same ones. `make`
is **not installed** on the development machine, so the raw command is given for each.

- Build / setup: `make setup` — `cd backend/ledger && ./mvnw -B dependency:go-offline`;
  `cd backend/assistant && uv sync --locked --extra dev`; `cd frontend/web && npm ci`
- Run: `make run` — `docker compose up --build` (starts PostgreSQL only). The ledger runs
  natively with `cd backend/ledger && ./mvnw spring-boot:run`; the web client with
  `cd frontend/web && npm run dev`
- Test (all): `make test`
- Test (ledger): `cd backend/ledger && ./mvnw -B verify`
- Test (assistant): `cd backend/assistant && uv run pytest`
- Test (web): `cd frontend/web && npm test`
- Test (single, ledger): `cd backend/ledger && ./mvnw -B test -Dtest=LedgerArchitectureTest`
- Test (single, assistant): `cd backend/assistant && uv run pytest tests/test_architecture.py -k <name>`
- Test (single, web): `cd frontend/web && npx vitest run -t <name>`
- Lint / types: `make lint` — `./mvnw -B spotless:check`; `uv run ruff check .`,
  `uv run ruff format --check .`, `uv run mypy src tests`; `npm run lint`,
  `npm run format:check`, `npm run typecheck`
- Format: `make format` — `./mvnw -B spotless:apply`; `uv run ruff format .`; `npm run format`
- Audit: `make audit` — `uv run pip-audit`; `npm audit --audit-level=high`
- Re-lock Python deps: `make lock` — `cd backend/assistant && uv lock` (required after any
  `pyproject.toml` dependency edit, or CI's `uv sync --locked` fails)

Keep this list in sync with the Development table in `README.md`.

## Layout

Organised **context first**: a bounded context is one directory containing its own hexagon,
so it can be extracted by moving it (ADR-010).

- `backend/ledger/` — Java 21, Spring Boot. Contexts `accounting/` and `budgeting/`, each with
  `domain/`, `application/{port/in,port/out,service}/`, `adapter/{in/web,out/persistence}/`,
  plus a top-level `shared/` kernel and `LedgerApplication`.
- `backend/assistant/` — Python 3.13, FastAPI. Contexts `portfolio/`, `assistance/`,
  `marketdata/`, each with `domain/`, `application/{port,usecase}/`, `adapters/`, plus
  `shared/`.
- `frontend/web/` — React 19, Vite 8, TypeScript. `src/features/` holds one directory per
  user-facing context; `src/shared/`; `src/app/` composes them.
- `contracts/` — the versioned OpenAPI contract. Empty until the first endpoint.
- `docs/ADRs/` — one file per decision, `ADR-NNN-short-kebab-title.md`, zero-padded and
  monotonically increasing. Append-only: supersede, never rewrite.
- `docs/requirements/` — what the system must do. `docs/architecture/` — what it actually is.
- `.claude/` — project agent configuration; tracked in git. Only
  `.claude/settings.local.json` is ignored.

## Conventions

These are enforced by the build, not by review. Breaking one fails CI.

- **No context may depend on another.** Not in Java, not in Python, not in the web client. The
  only common ground is each codebase's own `shared/`. Where two contexts must collaborate,
  the answer is a published port and an explicit translation, never an import.
- **The architecture is asymmetric on purpose** (ADR-007). Tactical DDD — aggregates, value
  objects, domain events — belongs in `backend/ledger/` only, where invariants exist. Do not
  add aggregates or repositories to the assistant; its cores are pure functions over frozen
  dataclasses.
- **Dependencies point inward** in every context. `LedgerArchitectureTest`,
  `backend/assistant/tests/test_architecture.py` and
  `frontend/web/tests/architecture.test.ts` enforce this.
- **The ledger domain imports no framework.** No `org.springframework..`, no `jakarta..`.
  Persistence entities live in `adapter/out/persistence` and are mapped — an entity is not an
  aggregate.
- **Money is never floating point.** `BigDecimal` in Java, `Decimal` in Python, `NUMERIC` in
  Postgres. A `double` or `float` field in a ledger domain fails the build.
- **The books balance.** A `Transaction` holds two or more entries summing to zero in one
  currency, checked in the constructor, so an unbalanced one is never constructed (ADR-011).
- **Derived, never stored.** An account balance is summed from entries and a budget's spend is
  passed in. If you find yourself adding a `balance` or `spent` field, that is the bug.
- **Amounts in two currencies are never combined** — no rates exist (ADR-012). Adding ILS to
  USD throws.
- **Pass the clock in.** No domain function calls `now()`. Transitions take a date, staleness
  takes the current time as an argument.
- **Foreign shapes stop at the anti-corruption layer.** Ledger DTOs, Anthropic responses and
  price-feed payloads become local model types in `adapters/outbound/*` and nowhere else.
- **No shared kernel across languages.** `Money` is implemented once per language and pinned
  by equivalent tests on each side; do not try to share it.
- **Test names are the specification:** `Given<scenario>_When<action>_Then<result>`. Ruff's
  `N802` is disabled for `tests/**` because of it.
- **Prove a new architecture rule fails.** Introduce a deliberate violation, confirm the build
  breaks, revert. A rule that has never failed is not evidence — and put the violation in a
  deep module, not a package `__init__.py`, which is where an earlier version of these rules
  was found to be blind.
- **Architecture rules are static.** ArchUnit reads bytecode; the assistant parses sources
  with `ast`; the web client walks the file tree. Do not implement one by importing the code
  and inspecting `sys.modules` — Python does not import subpackages, so such a rule sees
  almost nothing.

The ledger's five ArchUnit rules are **live**. The `archRule.failOnEmptyShould=false` escape
hatch was deleted with the first domain class, so a rule matching nothing now fails rather than
passing quietly. Do not reintroduce it.

TypeScript is pinned to 6.0.3, not 7.x, because `typescript-eslint` 8 declares
`typescript: >=4.8.4 <6.1.0` — past that, `npm ci` fails with ERESOLVE. Dependabot is
configured to ignore `typescript >=6.1.0` so it stops proposing the bump every week. Lift
both the pin and that ignore entry together, once typescript-eslint supports 7.
