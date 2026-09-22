# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

Four codebases — a Java ledger, a Java gateway, a Python assistant, a React web client — all
building and CI-gated. **All six bounded contexts have a domain layer and an application layer**,
with tests. Use cases sit behind ports and are wired by constructor. **The ledger's repository
ports are backed by PostgreSQL** (ADR-006, ratified 2026-09-06): hand-written SQL over
`JdbcClient`, Flyway migrations, a schema per bounded context, and a journal the database refuses
to rewrite (ADR-024). **The ledger now has an inbound HTTP adapter for each of its three contexts**
(accounting, budgeting, planning) under `/api/v1`, behind a `HostAllowListFilter` and problem+json
error handling, and an OpenAPI document generated from the code and committed at
`contracts/ledger-v1.openapi.json`, pinned by `LedgerContractIT`. ADR-004 is Accepted. **The
assistant now serves `portfolio` under `/api/v1` too**, behind `HostAllowList` and its own
problem+json handling in `adapters/problems.py`, with its OpenAPI document generated from the code
and committed at `contracts/assistant-v1.openapi.json`, pinned by `tests/test_contract.py`. An
opt-in, off-by-default development seed (`ASSISTANT_DEV_SEED`, `adapters/seed.py`) loads fictional
holdings and quotes so those endpoints have something to answer by hand; the assistant still has no
store, no ledger client and no price feed. **The web client has its accounting area**:
`AccountsPage` opens an account and lists every one with its derived balance,
`RecordTransactionPage` records a transaction by hand at the static route
`/accounts/transactions/new`, and `AccountDetailPage` shows one account's transactions and moves
each through `Clear`, `Reconcile` or `Void`, offering only the transitions its status legally
admits. **It also has its budgeting area**: `BudgetsPage` lists every budget and defines one for a
calendar month with a limit per category, `BudgetReviewPage` (`/budgets/:budgetId`) shows spend by
category — overspent, unbudgeted and uncategorised — taking the budget's own period and currency
from the cached list rather than a single-item read, and `CategorisationRulesPage`, at the static
route `/budgets/rules`, lists rules in the order they are tried and adds another; `/budgets/rules`
outranks the dynamic `/budgets/:budgetId` at the same depth by React Router's own
static-over-dynamic ranking. **It also has its planning area**: `PlannedFlowsPage` plans flows and
moves each through its lifecycle, `ScenariosPage` defines scenarios, `ProjectionPage`
(`/planning/projection`) charts a scenario's projection, with its first shortfall, against the cash
and invested accounts the owner chooses, and `ComparisonPage` (`/planning/compare`) compares two or
more scenarios against a baseline in tables. Planning's account choices come from accounting
through `PlanningAccountsBridge` in `src/app`, the web client's one cross-feature translation. All
three areas call the ledger through the dev proxy and the gateway, mapping generated wire types
onto each feature's own in `api/`. **It also has its portfolio area**, calling the assistant the
same way (ADR-026, extended by ADR-030): `PortfolioPage` looks a holding up by symbol, and
`HoldingPage` (`/portfolio/:symbol`) shows its position and valuation — nothing held and no usable
price as calm empty states rather than errors — and prices a disposal as a calculation that records
nothing. All four screen areas now exist; only "ask a question" is deliberately left out.
Observability is live (ADR-019): the ledger and the gateway answer Actuator probes, the ledger
traces its business endpoints too, and the assistant serves `/health` and `/ready` and assembles
its use cases at start-up. The ledger, the gateway and the assistant emit structured JSON logs
and OTLP traces, and all three run under Compose (`docker compose up`) as well as natively.
**Authentication and authorisation are built**: OIDC through a backend-for-frontend gateway
(ADR-030, which supersedes ADR-027), Keycloak (ADR-031), and role-based permissions owned by each
service (ADR-032).
`backend/gateway` signs the browser in against a self-hosted Keycloak (`ops/keycloak/`, with its
own `keycloak-db`), keeps every token in its server-side session, and relays each call to the
ledger or the assistant with `TokenRelay`; the browser holds only an `HttpOnly` session cookie and
a CSRF token, and the web client's dev proxy sends every call to the gateway. The ledger is a
resource server that validates bearer tokens and denies by default (`LedgerSecurityConfiguration`,
proven by `LedgerAuthorizationIT`); the assistant requires a bearer token and `portfolio:read` on
every business route, leaving `/health`, `/ready` and `/openapi.json` public
(`BearerAuthentication` and `RequirePermission` in `adapters/auth.py`). The web client gates
every screen on a session (`SessionGate`, `SessionContext`) and hides write controls from a
`viewer` (`useWriteAccess`), and every journal row written from `V1_2` onward records the token's
`sub` as `recorded_by` (ADR-033). `ops/smoke/auth_smoke.py` checks the wiring between them against
the running Compose stack, by hand and never in CI (ADR-034).

Read `docs/requirements/requirements.md` and `docs/ADRs/` before proposing work. No ADR is
Proposed today. Of all 36, ADR-027 is Superseded by ADR-030 and the other 35 are Accepted. The
requirements are still deliberately `TODO` in places rather than inferred from code.

## Commands

Every target below is a thin wrapper defined in `Makefile`; CI invokes the same ones. `make`
is **not installed** on the development machine, so the raw command is given for each.

- Build / setup: `make setup` — `test -f .env || cp .env.example .env`;
  `cd backend/ledger && ./mvnw -B -q dependency:go-offline`;
  `cd backend/gateway && ./mvnw -B -q dependency:go-offline`;
  `cd backend/assistant && uv sync --locked --extra dev`; `cd frontend/web && npm ci`
- Run: `make run` — `docker compose up --build` (starts PostgreSQL, Keycloak and its own
  database, the ledger, the gateway and the assistant; every published port binds `127.0.0.1`
  only, per ADR-030; `keycloak-db` publishes none at all). For the native inner loop:
  `cd backend/ledger && ./mvnw spring-boot:run` — it needs the database up first
  (`docker compose up -d db`), applies its Flyway migrations at start-up, and needs Keycloak up
  to verify a token, though not to start (no service runs OIDC discovery, ADR-030);
  `cd backend/gateway && ./mvnw spring-boot:run` — it needs Keycloak up first
  (`docker compose up -d keycloak-db keycloak`, or `make run-identity`);
  `cd backend/assistant && uv run python -m finassist_assistant.adapters`; the web client with
  `cd frontend/web && npm run dev`
- Stop: `make stop` — `docker compose down`, keeping volumes. Never `docker compose down -v`
  against the owner's own project: it deletes `db-data` along with `keycloak-db-data`
- Test (all): `make test`
- Test (ledger): `make test-ledger` — `cd backend/ledger && ./mvnw -B verify`
- Test (gateway): `make test-gateway` — `cd backend/gateway && ./mvnw -B verify`
- Test (assistant): `make test-assistant` — `cd backend/assistant && uv run pytest --cov
  --cov-report=term-missing --cov-report=xml`
- Test (web): `make test-web` — `cd frontend/web && npm run test:coverage`
- Test (single, ledger): `cd backend/ledger && ./mvnw -B test -Dtest=LedgerArchitectureTest`
- **Docker must be running for `make test-ledger` and `make test-gateway`.** The ledger's `*IT`
  tests drive the store against a real PostgreSQL in Testcontainers, and the gateway's
  `KeycloakRealmIT` builds `ops/keycloak/Dockerfile` and runs it against a real PostgreSQL; both
  fail rather than skip without Docker (ADR-025). Surefire (the unit suites) needs no Docker;
  failsafe does. `ApplicationYmlPlaceholdersTest` is a surefire test, so the ledger's `@Value`
  placeholder guard runs without Docker.
- Test (single, assistant): `cd backend/assistant && uv run pytest tests/test_architecture.py -k <name>`
- Test (single, web): `cd frontend/web && npx vitest run -t <name>`
- Build the web bundle: `make build-web` — `cd frontend/web && npm run build`
- Mutation testing (ledger): `make mutation-ledger` — `cd backend/ledger && ./mvnw -B -Pmutation
  test-compile org.pitest:pitest-maven:mutationCoverage`. A diagnostic, gated on nothing (ADR-017)
- Lint / types: `make lint` — `./mvnw -B spotless:check` (ledger and gateway);
  `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy src tests`; for the smoke
  check (`make lint-smoke`, its own CI step in the assistant job), `uv run ruff check`,
  `uv run ruff format --check` and `uv run mypy --strict` over `../../ops/smoke`, all from
  `backend/assistant`; `npm run lint`, `npm run format:check`, `npm run typecheck`
- Format: `make format` — `./mvnw -B spotless:apply` (ledger and gateway);
  `uv run ruff format . ../../ops/smoke`, `uv run ruff check --fix . ../../ops/smoke`;
  `npm run format`
- Audit: `make audit` — `uv run pip-audit`; `npm audit --audit-level=high`
- Re-lock Python deps: `make lock` — `cd backend/assistant && uv lock` (required after any
  `pyproject.toml` dependency edit, or CI's `uv sync --locked` fails)
- Run with telemetry: `make run-monitoring` — `docker compose --profile monitoring up --build`
  (collector, Prometheus, Grafana). `make stop-monitoring` tears the profile down
- Run identity only: `make run-identity` — `docker compose up -d keycloak-db keycloak`, for
  signing in or testing against Keycloak without the rest of the stack. Its realm
  (`ops/keycloak/finassist-realm.json`) imports only into an empty `keycloak-db-data` volume —
  edit it, or a secret it takes from `.env`, and nothing changes for an existing one until the
  realm is imported again. The README's "Changing the realm or its secrets" gives both recipes, a
  re-import with `kc.sh import --override true` or a recreated `keycloak-db-data`, and both delete
  the realm's users
- Smoke-test authentication (opt-in, manual): `make smoke-auth` — `docker compose --profile
  smoke run --rm auth-smoke`, after `make run` reports every service healthy (run cold, `compose
  run` starts the stack itself, but from existing images, without building). Runs
  `ops/smoke/auth_smoke.py` — standard library only, as an unprivileged user — inside the
  Compose network. Each service's own suite already tests its logic alone; this checks the
  wiring Compose gives them together, through the issuer Keycloak puts in a token fetched over
  the backchannel, the ledger's read and write rules for the assistant's token, the assistant's
  refusal of a token with the ledger's audience (there is no positive assistant check: that
  needs an interactive sign-in), and the gateway's 401-not-302 and login redirect (ADR-030
  through ADR-032). Never started by `docker compose up`; CI validates the service definition
  (`--profile smoke config`) and lints the script (`make lint-smoke`) but never runs the check
  itself
- Regenerate the ledger's OpenAPI contract: `cd backend/ledger && ./mvnw -B verify -Dtest=NONE
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LedgerContractIT` (Docker running); on a
  mismatch, copy `target/contracts/ledger-v1.openapi.json` over
  `contracts/ledger-v1.openapi.json`. Run this after every endpoint change
- Regenerate the assistant's OpenAPI contract: `cd backend/assistant && uv run pytest
  tests/test_contract.py` (no container needed); on a mismatch, copy
  `.contract-drift/assistant-v1.openapi.json` over `contracts/assistant-v1.openapi.json`. Run
  this after every endpoint change
- Regenerate the gateway's OpenAPI contract: `cd backend/gateway && ./mvnw -B verify -Dtest=NONE
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GatewayContractIT` (no container needed); on
  a mismatch, copy `target/contracts/gateway-v1.openapi.json` over
  `contracts/gateway-v1.openapi.json`. Run this after every endpoint change
- Regenerate the web client's contract types: `make generate-web-api` —
  `cd frontend/web && npm run generate:api`
- Clean: `make clean` — `./mvnw -B -q clean` in `backend/ledger` and `backend/gateway`, then
  delete the assistant's caches and coverage files and the web client's `dist/` and `coverage/`;
  `.env` and Docker volumes are left alone

Keep this list in sync with the Development table in `README.md`.

## Layout

Organised **context first**: a bounded context is one directory containing its own hexagon,
so it can be extracted by moving it (ADR-010).

- `backend/ledger/` — Java 21, Spring Boot. Contexts `accounting/`, `budgeting/` and
  `planning/`, each with `domain/`, `application/{port/in,port/out,service}/`,
  `adapter/{in/web,out/persistence,out/event}/`, plus a top-level `shared/` kernel, a
  `composition/` root and `LedgerApplication`.
- `backend/gateway/` — Java 21, Spring Boot, Spring Cloud Gateway Server Web MVC. The
  backend-for-frontend: it signs the browser in with OIDC (code flow, PKCE) against Keycloak,
  keeps the access and refresh tokens in its in-memory session, and relays `GET`/`POST
  /ledger/api/v1/**` and `/assistant/api/v1/**` with `StripPrefix`, `RemoveRequestHeader` and
  `TokenRelay` — routes declared in `application.yml`. It holds no bounded context: everything is
  in its `composition/` root, which is, in full: `GatewayConfiguration` (registers the Host
  allow-list first), `HostAllowListFilter` (a deliberate copy of the ledger's, configured by
  `GATEWAY_ALLOWED_HOSTS`), `GatewaySecurityConfiguration` (the one security chain, deny by
  default, 401 never 302, renamed CSRF cookie and header), `BffRoutes` (every `/bff/` path, in one
  place), `KeycloakRegistration` (the `ClientRegistration` built by hand, public and backchannel
  URLs split, and the client manager `TokenRelay` uses), `SessionController` (`GET /bff/me`),
  `SignInFailureHandler` (a failed sign-in back to `?signin=failed`),
  `SpaLogoutSuccessHandler` (`POST /bff/logout`), `ProblemAuthenticationEntryPoint` (401) and
  `ProblemAccessDeniedHandler` (403), `GatewayProblems` (the problem codes and writer),
  `GatewayRelayProblems` (a relay's failures), `GatewayOpenApiConfiguration` (the committed
  contract's shape), and the records `Origin`, `SessionResponse` and `LogoutResponse`. Its own
  Maven wrapper, so it builds standalone like the ledger.
- `backend/assistant/` — Python 3.13, FastAPI. Contexts `portfolio/`, `assistance/`,
  `marketdata/`, each with `domain/`, `application/{port,usecase}/`, `adapters/`, plus
  `shared/` and the composition root `adapters/` (`app.py`, `wiring.py`, `settings.py`,
  `observability.py`, `auth.py`, `problems.py`).
- `frontend/web/` — React 19, Vite 8, TypeScript. `src/features/` holds one directory per
  user-facing context; `src/shared/`; `src/app/` composes them, and `src/app/session/` gates the
  app on a signed-in session. `vite.config.ts` proxies `/ledger`, `/assistant` and `/bff` to the
  gateway.
- `contracts/` — the versioned OpenAPI contracts. Holds `ledger-v1.openapi.json`, generated by
  springdoc and pinned by `LedgerContractIT`; `assistant-v1.openapi.json`, generated by FastAPI
  and pinned by `tests/test_contract.py`; and `gateway-v1.openapi.json`, generated by springdoc
  and pinned by `GatewayContractIT`.
- `ops/` — infrastructure that is not this repository's application code: the collector,
  Prometheus and Grafana provisioning the `monitoring` Compose profile mounts, and
  `ops/keycloak/` — `Dockerfile` (builds the optimized Keycloak image that `docker-compose.yml`
  runs) and `finassist-realm.json` (the `finassist` realm, imported at start-up; every secret
  and credential in it is a `${VAR}` placeholder, pinned by `KeycloakRealmTest`, and the whole
  file is proved against a real Keycloak by `KeycloakRealmIT`, both in the gateway module).
  `ops/smoke/auth_smoke.py` is the only file behind the `smoke` Compose profile: a
  standard-library-only, opt-in smoke check of the authentication and RBAC wiring, run by hand
  with `make smoke-auth`, never by `docker compose up` and never by CI (ADR-034), and linted by
  `make lint-smoke`.
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
  Redaction lives at that boundary, never at a call site. `ops/smoke/auth_smoke.py` is an
  operator CLI, not a service: its report on stdout is its product, written by one `print`
  marked `# noqa: T201` with its reason.
- **Telemetry is a no-op when unconfigured.** A service must start with no collector named. An
  endpoint alone never enables export; `OTEL_EXPORT_ENABLED` does, for every signal — traces,
  metrics, and the ledger's OTLP log export, which nothing feeds until a log appender is added.
- **The assistant's composition root is `src/finassist_assistant/adapters/`.** It is the only
  package that may import FastAPI, structlog or OpenTelemetry outside a context's own
  `adapters`. No context may import it.
- **Adding a Python dependency can widen an architecture rule silently.** The forbidden list is
  derived from `pyproject.toml` and mangles `-` to `_`, so a distribution whose import root
  differs from its name (`opentelemetry-sdk` → `opentelemetry`) is missed unless named in the
  explicit set in `tests/test_architecture.py`. `jwt` and `cryptography` are named there for the
  same reason: `PyJWT[crypto]` mangles to the literal `PyJWT`, which matches neither `jwt` (the
  real import root) nor `pyjwt`, and `cryptography` arrives only transitively through the
  `crypto` extra, so no declared dependency name can ever produce it at all (ADR-030, ADR-032).
- **A refused token is WARN; an unreachable key source is ERROR, on both resource servers.** The
  first is one caller's problem, the second is an outage in which nobody can authenticate, so it
  gets a message of its own rather than the refusal line's — still the reason category alone,
  never the token. `ProblemAuthenticationEntryPoint.reportOutage` and
  `BearerAuthentication._report_unavailable`; pinned by `ProblemAuthenticationEntryPointTest`,
  `LedgerKeySourceOutageIT` and `tests/test_authorization.py`. Keep the two services symmetric.
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
- **Every journal insert names `recorded_by`; never validate the constraints.** `V1_2` added a
  nullable `recorded_by` and a `CHECK (...) NOT VALID` on `journal_transaction` and
  `journal_transaction_state`, so a row written before it honestly stays `NULL` while every new
  one must name a non-blank subject. `JdbcTransactionRepository` writes it on every `INSERT`;
  never run `ALTER TABLE ... VALIDATE CONSTRAINT` on either — it would scan the historical `NULL`
  rows and fail, and the append-only triggers forbid the `UPDATE` a fix would need (ADR-033).
- **`spring.flyway.out-of-order` stays `true`; never turn it off.** One history table with a major
  version per context (ADR-024) means the next migration for any context but the highest arrives
  below a version already applied, which Flyway classes `IGNORED` and refuses to start over — so
  with it false, `accounting` and `budgeting` could never gain another migration at all. Safe
  because no foreign key crosses a schema, so order between contexts means nothing; order within
  one is unchanged, and checksum validation is untouched. `LedgerUpgradeIT` is what turns red:
  it rebuilds the history a real database holds (`1.1`, `2.1`, `3.1`) from the committed scripts,
  byte for byte, then starts the ledger's own context against it. Extend it when the applied set
  changes, and never reach for `ignoreMigrationPatterns`, which would start the ledger with the
  column missing (ADR-035).
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
- **A controller calls in-ports only.** `inbound_adapters_reach_only_in_ports` fails the build
  for a controller reaching a repository, an outbound adapter, or a concrete `application.service`
  class; `outbound_adapters_do_not_reach_inbound` forbids the reverse.
- **Problem+json is layered.** A per-context `@RestControllerAdvice` runs at the highest
  precedence and maps that context's typed exceptions; `LedgerProblemHandler`, the composition
  fallback, runs at the lowest and catches what none of them claimed; a controller-local handler
  answers an id named in a body or query, since the same not-found exception is 404 from a path
  and 422 from a body (ADR-028).
- **The gateway's catch-all answers a framework refusal at its own status.**
  `GatewayRelayProblems.relayFailed` peels an `ErrorResponse` off first — 405
  `METHOD_NOT_ALLOWED` and 406 `NOT_ACCEPTABLE`, the only two `GET /bff/me` can provoke — and
  logs it at WARN, so ERROR still means a true 5xx. A status with no code in that map stays a
  500 rather than being passed through under an invented one. `GatewaySecurityTest` and
  `GatewayRelayProblemsTest` fail the build if either half is lost.
- **Neither Keycloak client is allowed the realm's full scope.** `fullScopeAllowed: false` on
  both, plus a realm-level `scopeMappings` entry naming exactly the roles each client's tokens
  need — `owner` and `viewer` for the gateway, `assistant` for the assistant. Left at Keycloak's
  default, every role in the realm reaches the `roles` claim. `KeycloakRealmTest` pins the file
  and `KeycloakRealmIT` reads the scope back from a running Keycloak through its admin API,
  because the gateway's client permits no grant this build can mint a token with.
- **Every decimal on the wire is a string, never a JSON number** — money, growth rates and
  quantities alike (ADR-028). The ledger refuses a JSON number in any text member with 400
  through `StrictTextCoercion`, proven against the running ledger by `AccountingHttpIT`. A
  `@WebMvcTest` slice that sends a number must `@Import` it, since a slice loads no scanned
  configuration.
- **A lifecycle date comes from the request body, never the clock** — "pass the clock in",
  applied at the edge.
- **A create answers `201` with the created representation and no `Location` header** — there is
  no single-item read for one to name (ADR-028).
- **Every POST `consumes` `application/json`.** A form post from a cross-site page is refused 415
  before a handler runs (ADR-030).
- **The Host allow-list lives in `composition`.** `HostAllowListFilter`, at the highest filter
  precedence, refuses a `Host` outside `LEDGER_ALLOWED_HOSTS` with 403 — before Actuator, and
  before any controller (ADR-030).
- **Every gateway call to Keycloak is built from Boot's own HTTP builders**, in
  `KeycloakRegistration`: the authorization-code client and the `refresh_token` client from
  `RestClient.Builder`, and the ID token's `JwtDecoderFactory` from `RestTemplateBuilder`. Spring
  Security's defaults for all three build their client from a static factory that never sees
  `spring.http.clients.*`, so dropping any of these beans silently makes that call unbounded —
  only the relay's own client gets Boot's budget for free. `GatewayKeycloakTimeoutIT` stalls each
  endpoint in turn and fails the build if one stops giving up.
- **Every outbound call the gateway makes speaks HTTP/1.1.** `GatewayConfiguration`'s
  `relayHttpVersion` customizer pins the JDK client's version, because its HTTP/2 default offers an
  h2c upgrade on a cleartext hop and uvicorn answers 400 to an upgrade request that carries a body
  — every POST relayed to the assistant. Boot exposes no property for this, so a
  `ClientHttpRequestFactoryBuilderCustomizer` bean is the lever; `GatewayLoginFlowIT` fails the
  build if an upgrade offer ever reaches an upstream again. Lift it only once a relayed hop runs
  over TLS, where HTTP/2 arrives by ALPN with no upgrade header at all.
- **A web DTO's simple name is unique across every context** — `MoneyDto` alone is exempt, one
  identical copy per context on purpose. `web_dto_names_are_unique_across_contexts`;
  `MoneyDtoShapesTest` keeps the copies identical.
- **The contract snapshot must be regenerated with every endpoint change**, or `LedgerContractIT`
  fails the build — see Commands above. It also refuses an `operationId` ending `_<digits>` (a
  springdoc collision) and a response schema missing `required` for an always-present member.
- **A fractional or decimal-pointed JSON number in an integer field is a 400**, `1.5` and `1.0`
  alike, ledger-wide (`spring.jackson.deserialization.accept-float-as-int: false`).
- **The assistant's router is a factory taking use cases, and never reads `app.state`.** Its
  handlers are `def`, never `async def` — every port behind them is synchronous (ADR-021).
- **Every assistant route declares exactly one permission dependency, built through the
  `require` factory the composition root passes into the router** (ADR-030, ADR-032) —
  `Depends(require(READ_PORTFOLIO))` on each of `portfolio_router`'s three routes, checked by a
  coverage test in `tests/test_authorization.py` that fails the build if a route is added
  without one. `RequirePermission`, the factory `app.py` passes as `require`, reads the
  `Principal` `BearerAuthentication` already verified from `request.scope`; it is never imported
  by the router itself, which would be the composition-root import the slice rule forbids.
- **`BearerAuthentication` runs inside the correlation middleware, not outside it** — added to
  the app before `correlate`, so Starlette's last-added-runs-first rule puts `correlate` outside
  it and a refused request still gets a correlation id and a request-answered line. The order is
  `HostAllowList` → correlate → auth → routes; `HostAllowList` stays outermost, added last, so a
  foreign `Host` is refused before authentication is even attempted.
- **The assistant's problem handlers live in the composition root**, `adapters/problems.py` —
  the one assistant package allowed to see every context's exceptions, mirroring
  `LedgerProblemHandler`.
- **Wire members are camelCase and currency is a `Literal` of ISO codes, resolved by code** — on
  both services.
- **No money amount travels in a URL, on either service.** A disposal calculation is priced by
  `POST` with a JSON body, never a `GET` with query parameters; none of the ledger's `GET`s
  carries one either.
- **The assistant's contract must be regenerated on any endpoint change**, exactly like the
  ledger's — see Commands above.
- **The web client's contract types under `src/shared/api/contracts/` are generated, never
  hand-written.** Regenerate them after either service's contract changes (`make
  generate-web-api`), and CI fails the `web` job on a diff it did not expect (ADR-029).
- **The assistant's development seed is off by default** (`ASSISTANT_DEV_SEED`) and lives only
  in the composition root (`adapters/seed.py`).
- **A logged 500 never carries an amount.** The guarantee is the assistant's problem handlers:
  `_withheld` logs a 500 caused by a validation failure through `_rejections`
  (`adapters/problems.py`): each error's `loc` and `type` alone, never its message, its input or
  `exc_info`, because FastAPI's `ResponseValidationError` quotes the input however a model is
  configured. The wire models' `hide_input_in_errors=True` is defence in depth for anything else
  that prints a pydantic `ValidationError`, not the guarantee. A 503 `PRICE_UNAVAILABLE` logs the
  event and the symbol only, never a traceback.
- **All three Host allow-lists refuse, at start-up, an entry naming a port or an IPv6 literal**:
  `ASSISTANT_ALLOWED_HOSTS` refuses one containing a `:` or a `[`, and each `HostAllowListFilter`
  — the ledger's and the gateway's copy — refuses a `:`, a `[` or a `]` in `LEDGER_ALLOWED_HOSTS`
  or `GATEWAY_ALLOWED_HOSTS`.
- **No service serves an API UI**: no `/docs` or `/redoc` on the assistant, and no Swagger UI on
  the ledger or the gateway, which depend on springdoc's `-api` starter alone. `/openapi.json`
  (and the ledger's own OpenAPI route) stays; the gateway's `/v3/api-docs` is denied by its
  security chain.
- **The ledger validates a bearer token and denies by default on every request** (ADR-030).
  `LedgerAuthorizationIT` checks this against an explicit, named inventory of every endpoint the
  running server answers to — plus springdoc's own and the container's error forward — so an
  endpoint added without a matching URL rule fails the build rather than shipping open.
- **Security lives in `composition`, never in a top-level `security` package.** A new context-shaped
  package would be treated as a bounded context by the slice rule and fail
  `contexts_do_not_depend_on_each_other`. `LedgerSecurityConfiguration`, `LedgerPermissions`,
  `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` all live there; a controller
  still touches only `java.security.Principal`.
- **Every `@WebMvcTest` slice imports `WebSliceSecurity`.** A slice loads no scanned configuration
  (ADR-028's convention, extended to ADR-030), so without it a slice would boot with no security at
  all — every request admitted, which is a false pass, not a fast one.
- **References to ADR-027 in code, configuration and docs point to ADR-030, in every codebase.**
  ADR-027 is Superseded; ADR-030 keeps its loopback binding, Host allow-lists and JSON-only writes
  as defence in depth, and is the record to cite for them going forward. ADR-027 is still named
  only by the ADR files themselves, by a statement of the supersession, and by the history note
  at the top of `docker-compose.yml`.

The ledger's ten ArchUnit rules are **live**. The `archRule.failOnEmptyShould=false` escape
hatch was deleted with the first domain class, so a rule matching nothing now fails rather than
passing quietly. Do not reintroduce it.

TypeScript is pinned to 6.0.3, not 7.x, because `typescript-eslint` 8 declares
`typescript: >=4.8.4 <6.1.0` — past that, `npm ci` fails with ERESOLVE. Dependabot is
configured to ignore `typescript >=6.1.0` so it stops proposing the bump every week. Lift
both the pin and that ignore entry together, once typescript-eslint supports 7.

## Review conventions (not build-enforced)

Nothing here fails CI. These are patterns to check for in review, worth naming so they stay
consistent rather than because a test would catch a deviation.

- **A web feature's `api/` is its anti-corruption layer.** It types each response with the
  generated wire type and maps it, member by member, onto the feature's own type in `types/`;
  nothing outside `api/` imports a generated type (ADR-029).
- **A web feature's query keys come from one factory constant**, such as `accountKeys`, never a
  key literal spelled at the call site. A mutation invalidates only the keys that can have
  changed, never a wider prefix that would refetch data nothing touched.
- **A web mutation's submit control disables itself while the mutation is pending**, and a
  submit event arriving anyway is ignored by an in-flight `useRef` guard, not the render-time
  `isPending` — two submit events can arrive before pending has rendered (ADR-028: no double
  submit).
- **A lifecycle date on the web client defaults to the browser's local today**, built through a
  helper that takes the date as an argument, such as `todayIsoDate(new Date())` — never
  `toISOString()`, which reads UTC and never `now()` called inside the helper itself.
- **A mutation that can affect an unknown number of cached queries invalidates them by a
  hierarchical key prefix, not by enumeration or a predicate.** `useAddCategorisationRule` cannot
  list which budgets exist, so `budgetKeys` gives every review a shared parent -
  `reviews: ['budgets', 'reviews']` and `review(id): ['budgets', 'reviews', id]` - and the
  mutation invalidates `queryKey: budgetKeys.reviews`, a plain prefix match that reaches every
  review and nothing else, since a sibling such as `list` sits outside that prefix by
  construction. Reach for `predicate` only when the affected keys cannot be given a common
  prefix without also catching a key that must not be invalidated. A mutation that already knows
  the affected ids (such as `useRecordTransaction` knowing which accounts an entry set names)
  still invalidates each key directly. A test proves the scope through the query client's own
  state (`getQueryState(key)?.isInvalidated`), not just the call's arguments.
- **A web date helper does calendar-part arithmetic on integers, never `Date` parsed from a
  string.** `period.ts`'s month-to-period mapping and day-before calculation use plain year/month
  integers with a leap-year-aware days-in-month table; a `Date` is built only to hand an already
  computed instant to `Intl.DateTimeFormat`, pinned to `timeZone: 'UTC'` exactly as `DateText`
  does, so formatting cannot shift the result with the browser's own zone.
- **Cross-feature data enters a web feature only through a context the feature declares and
  `src/app` provides.** Planning declares `AccountOptionsContext`, in its own terms, and
  `PlanningAccountsBridge` in `src/app` fills it from accounting's `useAccounts` (ADR-010,
  ADR-022). `tests/architecture.test.ts` enforces that no feature imports another; that the data
  arrives through a declared context rather than, say, a prop threaded from `src/app`, and that
  the hook reading it throws without a provider, is held in review.
- **A chart's scale function is the only place an amount becomes a number**, and only to compute a
  pixel coordinate - `scalePoints` in `features/planning/components/chartScale.ts`. Every figure a
  person reads - labels, axis extremes, tables, title text - is the server's string through
  `MoneyText` (ADR-029).
- **A form section scoped to one entity is keyed by that entity's id**, so navigating to another
  entity on the same route starts it afresh instead of carrying one entity's inputs or result onto
  the next. `HoldingPage` keys `DisposalSection` and its lookup by `symbol`; a test proves the reset
  by returning to an entity whose data is already cached, where only the key can cause a remount.
- **A lookup that can land on the URL already open invalidates that entity's key prefix**, since
  navigating to the same URL changes no query key and refetch on focus is off. `SymbolLookup`
  invalidates `portfolioKeys.holding(symbol)` through `useRefreshHolding`, pinned by a test
  asserting the whole invalidation set and that another cached entity stays valid.
- **Editing any field of a settled form clears its answer.** Each field's change handler calls
  `clearSettledOutcome(mutation)` from `src/shared/lib/settledMutation.ts`, which resets a mutation
  whose status is `error` or `success`, so its alert, its field errors or its result, such as a
  priced disposal, never stands beside inputs it no longer answers. A pending mutation is left
  alone: resetting it would detach the `onSettled` that releases the in-flight guard, and the form
  would stay locked. Each form has a test that editing a field clears its failure, and, for the
  disposal, its result.
- **A failed read offers "Try again" only when `isRetryable(error)` holds** — a network failure,
  or a 5xx other than a 503. The predicate lives in `src/shared/api/retry.ts`, and the query
  client's automatic retry imports the same one, so the two never disagree; a 4xx is the server's
  considered answer, shown with nothing to press. Every feature renders a failed read through the
  shared `QueryErrorState` from `src/shared/ui`, which renders its `RetryButton`, never a copy of
  its own; moves focus to what a successful retry restores with `useRetryFocus`; and checks a
  problem's code with `hasProblemCode`, beside `ApiError`.
- **A write control reads `useWriteAccess` before it renders, and the server enforces access
  regardless.** `WriteAccessContext` (`src/shared/lib/writeAccess.ts`) is a UX courtesy alone,
  never a security boundary; `SessionGate` provides it from `writeAccessFor(session.roles)`
  (`src/app/session/access.ts`), and `renderRoutes`'s `access` option defaults to
  `{canWrite: true}` so a screen test written before this convention needs no change. A viewer
  sees every area but no control that changes data — `OpenAccountForm`, `RecordTransactionPage`,
  Clear/Reconcile/Void, `DefineBudgetForm`, `AddCategorisationRuleForm`, `PlanFlowForm`,
  Commit/Realise/Cancel, and `DefineScenarioForm` all hide themselves this way — except
  `DisposalSection`, which stays offered because pricing a disposal is a read. A `FORBIDDEN`
  problem is rendered by `ErrorState` with a fixed sentence rather than the server's own detail,
  through the same `hasProblemCode` machinery, never a bespoke check.
