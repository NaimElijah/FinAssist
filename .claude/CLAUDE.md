# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

Three codebases — a Java ledger, a Python assistant, a React web client — all building and
CI-gated. **All six bounded contexts have a domain layer and an application layer**, with tests.
Use cases sit behind ports and are wired by constructor. **The ledger's repository ports are
backed by PostgreSQL** (ADR-006, ratified 2026-09-06): hand-written SQL over `JdbcClient`, Flyway
migrations, a schema per bounded context, and a journal the database refuses to rewrite
(ADR-024). Above that there is still nothing: **no inbound adapter, no HTTP endpoint and no
OpenAPI document**, because ADR-004 is still Proposed. The assistant has no store and needs
none — all five of its ports are outbound clients. Observability is live (ADR-019):
the ledger answers Actuator probes, the assistant serves `/health` and `/ready` and assembles
its use cases at start-up, and the web client renders a placeholder behind an error boundary.
Both services emit structured JSON logs and OTLP traces.

Read `docs/requirements/requirements.md` and `docs/ADRs/` before proposing work. Several
decisions are recorded but not yet ratified, and the requirements are deliberately `TODO`
rather than inferred from code.

## Commands

Every target below is a thin wrapper defined in `Makefile`; CI invokes the same ones. `make`
is **not installed** on the development machine, so the raw command is given for each.

- Build / setup: `make setup` — `cd backend/ledger && ./mvnw -B dependency:go-offline`;
  `cd backend/assistant && uv sync --locked --extra dev`; `cd frontend/web && npm ci`
- Run: `make run` — `docker compose up --build` (starts PostgreSQL only). The ledger runs
  natively with `cd backend/ledger && ./mvnw spring-boot:run` — it needs the database up first
  and applies its Flyway migrations at start-up; the web client with
  `cd frontend/web && npm run dev`
- Test (all): `make test`
- Test (ledger): `cd backend/ledger && ./mvnw -B verify`
- Test (assistant): `cd backend/assistant && uv run pytest --cov --cov-report=term-missing
  --cov-report=xml`
- Test (web): `cd frontend/web && npm run test:coverage`
- Test (single, ledger): `cd backend/ledger && ./mvnw -B test -Dtest=LedgerArchitectureTest`
- **Docker must be running for `make test-ledger`.** `*IT` tests drive the store against a real
  PostgreSQL in Testcontainers and fail rather than skip without it (ADR-025). Surefire (the
  unit suite) needs no Docker; failsafe does.
- Test (single, assistant): `cd backend/assistant && uv run pytest tests/test_architecture.py -k <name>`
- Test (single, web): `cd frontend/web && npx vitest run -t <name>`
- Mutation testing (ledger): `make mutation-ledger` — `cd backend/ledger && ./mvnw -Pmutation
  org.pitest:pitest-maven:mutationCoverage`. A diagnostic, gated on nothing (ADR-017)
- Lint / types: `make lint` — `./mvnw -B spotless:check`; `uv run ruff check .`,
  `uv run ruff format --check .`, `uv run mypy src tests`; `npm run lint`,
  `npm run format:check`, `npm run typecheck`
- Format: `make format` — `./mvnw -B spotless:apply`; `uv run ruff format .`; `npm run format`
- Audit: `make audit` — `uv run pip-audit`; `npm audit --audit-level=high`
- Re-lock Python deps: `make lock` — `cd backend/assistant && uv lock` (required after any
  `pyproject.toml` dependency edit, or CI's `uv sync --locked` fails)
- Run with telemetry: `make run-monitoring` — `docker compose --profile monitoring up --build`
  (collector, Prometheus, Grafana). `make stop-monitoring` tears the profile down

Keep this list in sync with the Development table in `README.md`.

## Layout

Organised **context first**: a bounded context is one directory containing its own hexagon,
so it can be extracted by moving it (ADR-010).

- `backend/ledger/` — Java 21, Spring Boot. Contexts `accounting/`, `budgeting/` and
  `planning/`, each with `domain/`, `application/{port/in,port/out,service}/`,
  `adapter/{in/web,out/persistence,out/event}/`, plus a top-level `shared/` kernel, a
  `composition/` root and `LedgerApplication`.
- `backend/assistant/` — Python 3.13, FastAPI. Contexts `portfolio/`, `assistance/`,
  `marketdata/`, each with `domain/`, `application/{port,usecase}/`, `adapters/`, plus
  `shared/` and the composition root `adapters/` (`app.py`, `wiring.py`, `settings.py`,
  `observability.py`).
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
  Persistence lives in `adapter/out/persistence` and maps rows to aggregates by hand; there is
  no entity type at all, which is the strongest form of "an entity is not an aggregate". The
  `Money` mapping is duplicated per context on purpose — the slice rule leaves no legal shared
  home, and ADR-021 already refused to let coupling accumulate in `shared`.
- **Money is never floating point.** `BigDecimal` in Java, `Decimal` in Python, `NUMERIC` in
  Postgres. A `double` or `float` field in a ledger domain fails the build.
- **The books balance.** A `Transaction` holds two or more entries summing to zero in one
  currency, checked in the constructor, so an unbalanced one is never constructed (ADR-011).
- **Derived, never stored.** An account balance is summed from entries, a budget's spend is
  passed in, and a projection is computed from its scenario. If you find yourself adding a
  `balance`, `spent` or stored-projection field, that is the bug.
- **Amounts in two currencies are never combined** — no rates exist (ADR-012). Adding ILS to
  USD throws.
- **Pass the clock in.** No domain function calls `now()`. Transitions take a date, staleness
  takes the current time as an argument, and a projection takes its horizon.
- **A plan is not a fact.** `planning/` holds what the owner intends; `accounting/` holds what
  happened. They are separate contexts so the two can never be summed together (ADR-015).
  A `PlannedFlow` amount is always positive and its `Direction` carries the sign — the opposite
  of `Entry`, which is signed because double entry must balance.
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
- **Nothing writes output except the logging boundary.** No `print` (ruff `T20`), no
  `console.log` (ESLint `no-console`), no `System.out`. Structured JSON on stdout, through
  `adapters/observability.py`, `shared/lib/logger.ts`, or Logback's ECS format (ADR-019).
  Redaction lives at that boundary, never at a call site.
- **Telemetry is a no-op when unconfigured.** A service must start with no collector named. An
  endpoint alone never enables export; `OTEL_EXPORT_ENABLED` does, for traces and metrics
  alike.
- **The assistant's composition root is `src/finassist_assistant/adapters/`.** It is the only
  package that may import FastAPI, structlog or OpenTelemetry outside a context's own
  `adapters`. No context may import it.
- **Adding a Python dependency can widen an architecture rule silently.** The forbidden list is
  derived from `pyproject.toml` and mangles `-` to `_`, so a distribution whose import root
  differs from its name (`opentelemetry-sdk` → `opentelemetry`) is missed unless named in the
  explicit set in `tests/test_architecture.py`.
- **Logs are diagnostics, never the audit trail.** They are dropped when no collector is
  attached. The append-only journal is the audit record (ADR-020).
- **The application layer imports no framework, in either language.** No `@Service`, no
  `@Transactional`, no pydantic, no FastAPI. A use case is a plain object taking its ports as
  constructor arguments; the annotations live in the composition root. Enforced by
  `the_application_layer_depends_on_no_framework` and by the assistant's framework rule.
- **Cross-context collaboration is a port the consumer owns plus a translation outside both.**
  `budgeting` declares `SpendJournal`; `planning` declares `OpeningPositionProvider` and
  addresses accounts by its own `AccountRef`. Neither names an accounting type. The
  translations live in `ledger/composition/` and `assistant/adapters/wiring.py` — the only
  packages that may see two contexts, and the only ones nothing may import back into. An
  adapter inside a context is **not** a legal home for one; the slice rule has no layer
  qualifier (ADR-022).
- **A translation calls the upstream context's in-port, never its repositories**, and converts
  its exceptions at the boundary. An `AccountNotFoundException` must not reach planning.
- **Repository ports expose no delete.** A lifecycle change is stored by saving the aggregate
  in its new state. That is ADR-020's append-only obligation as a port shape, so the durable
  adapter inherits it (ADR-021).
- **Domain events are ledger-only, and raised by the application service.** The aggregates are
  records whose value semantics the suites rely on, so they carry no pending-events list. Only
  `accounting` and `planning` raise events — a `Budget` has no lifecycle. Nothing subscribes
  yet, deliberately (ADR-023).
- **Identity is generated inside the service, never accepted on a command.**
- **A use case takes its dates as arguments too.** The clock is read once, in the composition
  root (`utc_now`), and passed down — the same rule the domain follows.
- **The assistant's ports are synchronous** (ADR-021). Do not add `async` without the trigger:
  a use case that must fan out concurrent I/O. `pytest-asyncio` is not a dependency.
- **The ledger's store is PostgreSQL, and the journal is append-only in the database.** Triggers
  on `accounting.journal_transaction`, `journal_entry` and `journal_transaction_state` refuse an
  `UPDATE` or a `DELETE`; a lifecycle change is an `INSERT` whose highest `sequence` is the
  current state. Do not add an `UPDATE` to `JdbcTransactionRepository` — the database will refuse
  it, which is the point (ADR-020, ADR-024). `budgeting` and `planning` are ordinary mutable
  tables: ADR-020 binds the record of what happened, and ADR-015 puts intent in another context.
- **No foreign key crosses a schema.** `SchemaBoundariesIT` fails the build if one does. It is
  the storage-layer half of `contexts_do_not_depend_on_each_other`, which reads bytecode and so
  stops at the edge of the JVM.
- **Money is `numeric` with no declared precision.** PostgreSQL preserves the exact scale it is
  given, and `Money` rejects rather than rounds excess precision. `numeric(19,2)` would break JPY,
  which has no minor units.
- **The only in-memory adapters left are the two event publishers.** Nothing subscribes yet
  (ADR-023), and an outbox with no consumer is what ADR-020 refused to build ahead of one.
- **`@Transactional` goes on the adapter, never on an application service** —
  `the_application_layer_depends_on_no_framework` fails the build for the latter. When a use case
  must write across two aggregates, the boundary moves to a decorator in `composition`.

The ledger's seven ArchUnit rules are **live**. The `archRule.failOnEmptyShould=false` escape
hatch was deleted with the first domain class, so a rule matching nothing now fails rather than
passing quietly. Do not reintroduce it.

TypeScript is pinned to 6.0.3, not 7.x, because `typescript-eslint` 8 declares
`typescript: >=4.8.4 <6.1.0` — past that, `npm ci` fails with ERESOLVE. Dependabot is
configured to ignore `typescript >=6.1.0` so it stops proposing the bump every week. Lift
both the pin and that ignore entry together, once typescript-eslint supports 7.
