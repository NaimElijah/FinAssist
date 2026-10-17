# CLAUDE.md

Guidance for Claude Code in this repository. This file holds what binds every codebase; each
codebase's own `CLAUDE.md` holds the rest (ADR-042).

## The system

Four codebases, all building and CI-gated, plus `ops/`. All six bounded contexts have a domain
layer and an application layer, with tests; use cases sit behind ports, wired by constructor.

- `backend/ledger` (Java 21, Spring Boot) — `accounting`, `budgeting` and `planning`, stored in
  PostgreSQL and served under `/api/v1`.
- `backend/assistant` (Python 3.13, FastAPI) — `portfolio`, `assistance` and `marketdata`. Only
  `portfolio` is served; there is no store, ledger client or price feed yet.
- `backend/gateway` (Java 21, Spring Cloud Gateway Server Web MVC) — the backend-for-frontend: it
  signs the browser in against a self-hosted Keycloak (ADR-030, ADR-031), keeps every token
  server-side, and relays each call to the ledger or the assistant with `TokenRelay`.
- `frontend/web` (React 19, Vite 8, TypeScript) — the accounting, budgeting, planning and portfolio
  areas, through the gateway. Only "ask a question" is deliberately left out.

Each service owns its permissions and denies by default (ADR-032); the web client hides write
controls from a `viewer`. The services log structured JSON and emit OTLP traces (ADR-019).

**Before proposing work**, read `docs/requirements/requirements.md` and the relevant records in
`docs/ADRs/` (`docs/ADRs/README.md` gives each one's status). The requirements are `TODO` in places
on purpose: never fill a gap by inferring it from code. `docs/architecture/architecture.md`
describes the system as built.

**A codebase's own file loads only once a file under it is read or edited, so read it before
planning work there:** `backend/ledger/CLAUDE.md`, `backend/gateway/CLAUDE.md`,
`backend/assistant/CLAUDE.md`, `frontend/web/CLAUDE.md`, `ops/CLAUDE.md`.

## Commands

Every target is a thin wrapper in `Makefile` that CI also calls. **`make` is not installed on the
development machine**, so run the raw command: cross-cutting ones below, each codebase's in its
file (`make lint`, `make format` and `make audit` run them all; `ops/CLAUDE.md` has identity,
backups and the smoke check). Keep them in sync with `README.md`'s Development table.

- **Set up** — `make setup`: `test -f .env || cp .env.example .env`;
  `./mvnw -B -q dependency:go-offline` in `backend/ledger` and `backend/gateway`;
  `uv sync --locked --extra dev` in `backend/assistant`; `npm ci` in `frontend/web`.
- **Run** — `make run`: `docker compose up --build` (PostgreSQL, Keycloak and its own database,
  the ledger, the gateway, the assistant); every published port binds `127.0.0.1` only (ADR-030),
  and `keycloak-db` publishes none. **Stop** — `make stop`: `docker compose down`, keeping volumes.
  **Never `docker compose down -v`**: it deletes the owner's `db-data` and `keycloak-db-data`.
- **Telemetry** — `make run-monitoring`: `docker compose --profile monitoring up --build`
  (collector, Prometheus, Grafana); `make stop-monitoring` tears the profile down.
- **Test** — `make test` runs `test-ledger`, `test-gateway`, `test-assistant`, `test-backup` and
  `test-web`. **Docker must be running for the ledger's and the gateway's suites**: their `*IT`
  tests use Testcontainers and fail rather than skip without it (ADR-025).
- **Clean** — `make clean`: `./mvnw -B -q clean` (ledger, gateway), then delete the assistant's
  caches and coverage files and the web client's `dist/` and `coverage/`; never `.env` or volumes.
- **After every endpoint change**, regenerate that service's contract (the command is in its file;
  its contract test fails the build otherwise), then the web client's types:
  `make generate-web-api`, which is `cd frontend/web && npm run generate:api`.

## Layout

Organised **context first**: a bounded context is one directory holding its own hexagon, so it can
be extracted by moving it (ADR-010). Each codebase's file gives its package shape.

- `backend/ledger/`, `backend/gateway/` (each with its own Maven wrapper), `backend/assistant/`,
  `frontend/web/` — the codebases. `ops/` — infrastructure that is not application code.
- `contracts/` — the versioned OpenAPI documents, each pinned by a test: `ledger-v1.openapi.json`
  (`LedgerContractIT`), `assistant-v1.openapi.json` (`tests/test_contract.py`),
  `gateway-v1.openapi.json` (`GatewayContractIT`); and `money-vectors.v1.json`.
- `docs/ADRs/` — `ADR-NNN-short-kebab-title.md`, numbered monotonically, append-only: supersede,
  never rewrite. `docs/requirements/` — what the system must do; `docs/architecture/` — what it is.
- `.claude/` — agent configuration, tracked in git; only `.claude/settings.local.json` is ignored.

## Rules that bind every codebase

Enforced by the build unless marked otherwise: breaking one fails CI.

### Architecture

- **No context may depend on another** — not in Java, Python or the web client. The only common
  ground is each codebase's own `shared/`; contexts collaborate through a published port and an
  explicit translation, never an import.
- **Dependencies point inward** in every context: `LedgerArchitectureTest`,
  `backend/assistant/tests/test_architecture.py`, `frontend/web/tests/architecture.test.ts`.
- **The architecture is asymmetric on purpose** (ADR-007). Tactical DDD — aggregates, value
  objects, domain events — belongs in `backend/ledger/` only, where invariants exist.
- **The application layer imports no framework, in either language** — no `@Service`, no
  `@Transactional`, no pydantic, no FastAPI. A use case is a plain object taking its ports as
  constructor arguments; annotations live in the composition root.
  `the_application_layer_depends_on_no_framework` and the assistant's framework rule enforce it.
- **Cross-context collaboration is a port the consumer owns plus a translation outside both.**
  `budgeting` declares `SpendJournal`; `planning` declares `OpeningPositionProvider` and addresses
  accounts by its own `AccountRef`; neither names an accounting type. Translations live in
  `ledger/composition/` and `assistant/adapters/wiring.py`, the only packages that may see two
  contexts and that nothing may import back. An adapter inside a context is **not** a legal home
  for one: the slice rule has no layer qualifier (ADR-022).
- **A translation calls the upstream context's in-port, never its repositories**, and converts its
  exceptions at the boundary: an `AccountNotFoundException` must not reach planning.
- **Identity is generated inside the service, never accepted on a command.**

### The domain

- **Money is never floating point**: `BigDecimal` in Java, `Decimal` in Python, `NUMERIC` in
  PostgreSQL. A `double` or `float` field in a ledger domain fails the build.
- **Amounts in two currencies are never combined** — no rates exist (ADR-012). ILS plus USD throws.
- **No shared kernel across languages.** `Money` is implemented once per language; do not try to
  share it. Both are pinned by `contracts/money-vectors.v1.json` (ADR-018), read by
  `MoneyVectorsTest` and `tests/test_money_vectors.py`. Each pins the set of groups it reads, so a
  new group fails both suites until Java and Python each read it.
- **Derived, never stored.** A balance is summed from entries, a budget's spend passed in, a
  projection computed from its scenario: a `balance`, `spent` or stored-projection field is the bug.
- **Pass the clock in.** No domain function calls `now()`: transitions take a date, staleness the
  current time, a projection its horizon. Use cases take their dates as arguments too; the clock is
  read once, in the composition root (`utc_now`), and passed down.
- **A plan is not a fact.** `planning/` holds what the owner intends, `accounting/` what happened;
  separate contexts, so the two can never be summed (ADR-015).

### The wire

- **Every decimal is a string, never a JSON number** — money, growth rates and quantities alike
  (ADR-028). Members are camelCase; currency is a `Literal` of ISO codes, resolved by code.
- **No money amount travels in a URL**: a disposal is priced by `POST` with a JSON body, and none
  of the ledger's `GET`s carries one either.
- **A lifecycle date comes from the request body, never the clock** — "pass the clock in".
- **A create answers `201` with the created representation and no `Location` header**: there is no
  single-item read for one to name (ADR-028).
- **Every POST `consumes` `application/json`**, so a cross-site form post is refused 415 before a
  handler runs (ADR-030).

### Security and operations

- **All three Host allow-lists refuse, at start-up, an entry naming a port or an IPv6 literal**:
  `ASSISTANT_ALLOWED_HOSTS` a `:` or `[`; `LEDGER_ALLOWED_HOSTS` and `GATEWAY_ALLOWED_HOSTS`
  (each `HostAllowListFilter`, the gateway's a copy of the ledger's) a `:`, `[` or `]`.
- **A refused token is WARN; an unusable key source is an outage, logged ERROR in a message of its
  own** — reason category only, never the token — on both resource servers (ADR-037). Unreachable,
  not a key set, or a set with no key: an outage. A set holding keys that none verifies: a refusal.
  **Keep the two services symmetric**; each one's file says where it tells them apart.
- **No service serves an API UI**: no `/docs` or `/redoc` on the assistant, no Swagger UI on the
  ledger or the gateway (springdoc's `-api` starter alone). `/openapi.json` and the ledger's own
  OpenAPI route stay; the gateway's `/v3/api-docs` is denied by its security chain.
- **Cite ADR-030, never the Superseded ADR-027**, whose loopback binding, Host allow-lists and
  JSON-only writes ADR-030 keeps as defence in depth. ADR-027 is named only by the ADR files, a
  statement of the supersession, and the history note atop `docker-compose.yml`.
- **Nothing writes output except the logging boundary** — no `print` (ruff `T20`), no
  `console.log` (ESLint `no-console`), no `System.out`. Structured JSON on stdout through
  `adapters/observability.py`, `shared/lib/logger.ts` or Logback's ECS format (ADR-019); redaction
  lives at that boundary, never at a call site. The operator CLIs in `ops/` are the one exception.
- **Telemetry is a no-op when unconfigured**: a service starts with no collector named. An endpoint
  alone never enables export; `OTEL_EXPORT_ENABLED` does, for every signal — traces, metrics, and
  the ledger's OTLP log export, which nothing feeds until a log appender is added.
- **Logs are diagnostics, never the audit trail.** They are dropped when no collector is attached;
  the append-only journal is the audit record (ADR-020).

### Compose (`docker-compose.yml`)

- **Every long-running service takes `restart: *restart-policy` and `logging: *bounded-logs`; the
  one-shot `auth-smoke` takes neither.** The two `x-` blocks at the top state the policy
  (`unless-stopped`) and the bound (three 10 MB `json-file` logs) once (ADR-039, ADR-019).
- **Every healthcheck probes readiness**: `/actuator/health/readiness` on the ledger and the
  gateway, `/ready` on the assistant, `/health/ready` on Keycloak's management port. CI's `compose`
  job fails a long-running service whose restart or logging is not exactly those blocks,
  `auth-smoke` with either, and any of those four healthchecks that is not readiness (#142).
- **`auth-smoke` stays behind `profiles: [smoke]`**, so `docker compose up` never starts it. CI
  validates it (`--profile smoke config`) but never runs it, nor `make restore-check` (ADR-034,
  ADR-038).

### Tests

- **Test names are the specification:** `Given<scenario>_When<action>_Then<result>`; ruff's `N802`
  is disabled for `tests/**` because of it.
- **Prove a new architecture rule fails**: introduce a deliberate violation, confirm the build
  breaks, revert. Put it in a deep module, not a package `__init__.py`, where an earlier version of
  these rules was found to be blind. A rule that has never failed is not evidence.
- **Architecture rules are static**: ArchUnit reads bytecode, the assistant parses sources with
  `ast`, the web client walks the file tree. Never import the code and inspect `sys.modules` —
  Python does not import subpackages, so such a rule sees almost nothing.

## Working in this repository

- **Git**: branch `type/short-kebab` off `dev`; PR into `dev` with `Refs #N`; `dev` reaches `main`
  by a merge commit whose PR says `Closes #N` — never squash, rebase or delete `dev`. The owner's
  `/workflow-gitwise` runs the whole cycle. Common scopes: `ledger`, `gateway`, `assistant`, `web`,
  `ops`, `compose`, `ci`, `deps`, `contracts`, `adr`, `claude`. Link files in a PR body by full
  `https://github.com/NaimElijah/FinAssist/blob/` URL. `CHANGELOG.md` is written by hand, whatever
  its header says: every PR that changes code adds a bold-lead bullet under `## [Unreleased]`.
- **A new ADR** takes the next number, a row and a dated note in `docs/ADRs/README.md`, and an
  "extended by NNN" in each extended record's Status line and in its row in that README.
- **A CI job already red on `dev`** is fixed as its own commit in the PR it blocks; ask the owner
  first if that bumps a runtime security dependency.
- **Run what you change.** Code here has repeatedly passed only because a test supplied something
  — a config key, a migration order, a test-scoped jar (ADR-040). Prove such changes by starting
  the packaged artefact or the stack in an isolated Compose project (`-p finassist-<purpose>`,
  moved ports, fresh volumes, a throwaway `--env-file`), never the owner's `finassist` project.
- **Spring** (Boot 4.1, Security 7, Cloud 2025.1) differs sharply from older material: check an API
  against the jars before using it. Send a foreign `Host` header over a raw socket — the JDK
  `HttpClient` will not — and read filter order from the running server's own chain.
- **This machine** (Windows, Git Bash): `Path.write_text` writes CRLF, so write repo files with
  `write_bytes`; set `MSYS_NO_PATHCONV=1` to pass a container path such as `/opt/...`; put a
  worktree at a short path such as `C:/Users/naimo/fa<N>`, not under the scratchpad ("Filename too
  long"). `.claude/worktrees/` is excluded locally; leave it out of searches.
