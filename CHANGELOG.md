# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries are generated from the [Conventional Commit](https://www.conventionalcommits.org/)
history; write the commit message well and this file writes itself.

## [Unreleased]

### Added

- **A real sign-in, and every business request authenticated.** The browser signs in with OIDC,
  the authorization-code flow with PKCE, at a new backend-for-frontend, `backend/gateway`
  (Spring Cloud Gateway Server Web MVC). The gateway keeps the access and refresh tokens in its
  server-side session, gives the browser only an `HttpOnly` session cookie and a CSRF token, and
  relays each call to the ledger or the assistant with the access token attached. It answers an
  unauthenticated request 401, never a redirect, and publishes its own contract,
  `contracts/gateway-v1.openapi.json`. There is no switch that turns authentication off. See
  [ADR-030](docs/ADRs/ADR-030-oidc-authentication-through-a-bff-gateway.md), which supersedes
  ADR-027.
- **Keycloak as the identity provider**, self-hosted under Compose with its own PostgreSQL,
  `keycloak-db`, and its `finassist` realm committed as code in `ops/keycloak/`. The owner's
  first sign-in sets a new password and enrols TOTP. See
  [ADR-031](docs/ADRs/ADR-031-keycloak-as-the-identity-provider.md).
- **The ledger and the assistant are resource servers that deny by default.** Each validates a
  bearer token against an injected clock and decides what it may do from its own role table:
  `owner` reads and writes, `viewer` only reads, and the assistant's own `assistant` role only
  reads the ledger. A ledger endpoint added without a URL rule, or a route added to the
  assistant's router without a permission, fails the build. See
  [ADR-032](docs/ADRs/ADR-032-role-based-authorization-per-service.md).
- **The web client signs in and out**, shows who is signed in, and hides every control that
  changes data from a `viewer`. The services enforce the same rule regardless.
- **The journal records who wrote each row**, from `V1_2__journal_recorded_by.sql` onward. Rows
  written before it honestly name nobody. See
  [ADR-033](docs/ADRs/ADR-033-the-journal-records-who.md).
- **An opt-in smoke check of the authentication wiring**, `ops/smoke/auth_smoke.py`, run by hand
  with `make smoke-auth` against the Compose stack. CI lints it and validates its Compose
  definition, but never runs it. See
  [ADR-034](docs/ADRs/ADR-034-keep-the-compose-smoke-check-manual.md).
- **The ledger's books are stored in PostgreSQL.** Three schemas, one per bounded context, with
  no foreign key crossing one — the storage-layer counterpart of the rule ArchUnit enforces in
  the bytecode, and the answer to whether planned data shares a store with the books: the same
  database, never the same schema, never a join. Flyway migrations live in a directory per
  context, each owning a major version so two can never collide. Hand-written SQL over Spring's
  `JdbcClient`; no ORM, so every statement the ledger issues is readable in one place. See
  [ADR-006](docs/ADRs/ADR-006-postgresql-for-the-ledger-store.md), ratified with this work, and
  [ADR-024](docs/ADRs/ADR-024-jdbc-persistence-and-append-only-schema.md).
- **The audit trail [ADR-020](docs/ADRs/ADR-020-audit-trail-follows-persistence.md) attached to
  this exact commit.** That record required the mapping to preserve the journal's append-only
  character "as a property of the schema and its migrations", and it is: a trigger on each of the
  three accounting tables refuses an `UPDATE` or a `DELETE` outright, and a lifecycle change is an
  `INSERT` into `journal_transaction_state` whose highest sequence is the current state. The
  guarantee therefore does not depend on the adapter's restraint, and `JournalIsAppendOnlyIT`
  proves it by issuing both statements and watching the database refuse them.
- **The database enforces what the domain enforces**, so a bug in one is caught by the other —
  the pairing ADR-006 asked for. `CHECK` constraints mirror each sealed type variant for variant;
  a unique index on the normalised account name is what finally makes `AccountRepository.save`
  atomic, as its Javadoc has promised since ADR-021; and a deferred constraint trigger refuses a
  transaction whose entries do not sum to zero, mix currencies, or post to an account in a
  currency it does not hold.
- **Integration tests against a real PostgreSQL**, on the image tag Compose pins, bound to
  failsafe as `*IT` — the decision [ADR-017](docs/ADRs/ADR-017-test-strategy.md) deferred until
  ADR-006 was ratified. Docker is now required to test the ledger and the suite fails rather than
  skipping without it, because a green suite that exercised none of the SQL is worse than one
  that refuses to run ([ADR-025](docs/ADRs/ADR-025-integration-tests-on-testcontainers.md)).
  `SchemaBoundariesIT` gives context isolation a check the bytecode rules cannot reach.


- **An application layer in all six bounded contexts.** Use cases behind ports, wired by
  constructor and backed by in-memory adapters that are a development seam rather than a store.
  In the ledger: opening an account, recording a transaction and moving it through its
  lifecycle, reading a balance and the journal, defining and reviewing a budget, planning a
  flow, naming a strategy, projecting it and comparing strategies. In the assistant: reading and
  valuing a position, disposing of units, fetching a quote and refusing a stale one, and
  answering a question that is rejected when it states a figure it cannot cite. See
  [ADR-021](docs/ADRs/ADR-021-application-layer-shape.md).
- **Two requirements that could never have been domain rules are now enforced.** An entry must
  be in its account's currency — an `Entry` holds an `AccountId`, not an `Account`, so checking
  it always needed a repository — and an account name is unique across the chart of accounts,
  which is a property of the set rather than of any value. Both had sat marked `TODO` with that
  explanation since the domain landed.
- **A composition root in the ledger**, `io.github.naimelijah.finassist.ledger.composition`,
  mirroring the one the assistant has had since ADR-019. It holds the Spring wiring and the two
  translations that carry a figure between contexts, because the slice rule has no layer
  qualifier and leaves nowhere else legal. Its exemption is one-way and enforced in both
  directions. See [ADR-022](docs/ADRs/ADR-022-ledger-composition-root.md).
- **Two more live ArchUnit rules**, taking the ledger to seven:
  `no_context_depends_on_the_composition_root`, and
  `the_application_layer_depends_on_no_framework` — which turns a promise `LedgerApplication`
  had made since the scaffold into a build failure. Each was confirmed to fail against a
  violation planted in a deep module, then reverted.
- **Domain events in `accounting` and `planning`**, raised by the application service rather
  than the aggregate, published through an out-port and recorded in an append-only log. Nothing
  subscribes; [ADR-023](docs/ADRs/ADR-023-domain-events-in-the-ledger.md) records that as the
  accepted cost and names the trigger that discharges it.

### Changed

- **The web client's dev proxy sends every call to the gateway**, never straight to the ledger or
  the assistant, and the app must be opened at `http://localhost:5173`, not `127.0.0.1`, because
  the session cookie is keyed by host name.
- **An existing `.env` needs five new secrets** — `KEYCLOAK_ADMIN_PASSWORD`,
  `KEYCLOAK_DB_PASSWORD`, `FINASSIST_OWNER_INITIAL_PASSWORD`, `GATEWAY_CLIENT_SECRET` and
  `ASSISTANT_CLIENT_SECRET` — or `docker compose up` refuses to start and names each one missing.
- **The six in-memory repositories are deleted, not kept behind a profile.** They were a
  development seam while ADR-006 was open; a second implementation satisfying none of the
  persistence requirement is a code path nothing should be able to select by accident. The
  application services are now tested through mocked ports, so the unit suite still needs no
  database. The two event publishers stay in memory: nothing subscribes to a domain event yet
  (ADR-023), and an outbox with no consumer is what ADR-020 refused to build ahead of one.
- **Readiness stops being liveness by another name.** It includes the `db` indicator, so a ledger
  that cannot reach its store reports `DOWN` — there is finally something real to be unready for.
- **`LEDGER_DB_URL` defaults to `localhost`** rather than the Compose service name `db`, which
  resolved to nothing: the ledger runs natively while only Postgres is in the Compose file.

### Fixed

- **The ledger starts against a database that already holds the books.** `V1_2` is numbered below
  `2.1` and `3.1`, which every existing database has applied, and Flyway refuses an unapplied
  lower version unless it is told to run out of order — so the ledger would have exited at
  start-up on the owner's own volume, leaving every screen answering 502. `spring.flyway.out-of-order`
  is now `true`, which a major version per context requires in any case, and `LedgerUpgradeIT`
  rebuilds that history from the committed scripts and starts the ledger against it, so the next
  such migration fails the build instead. See
  [ADR-035](docs/ADRs/ADR-035-flyway-runs-out-of-order.md).
- **The ledger refuses a token carrying no `exp`.** Spring's `JwtTimestampValidator` allows a
  missing expiry unless told otherwise, so a token signed with the realm's key and given no
  lifetime at all was accepted forever — and answered `200` — while the assistant, which enforces
  the claim, refused the same token. ADR-030 requires `exp` of both.
- **Neither Keycloak client is allowed the realm's full scope.** Both defaulted to it, so the
  gateway client's scope held every role in the realm — `default-roles-finassist`,
  `offline_access`, `uma_authorization` and even `assistant`, the service principal's own role.
  Nothing granted anything for them, but a `roles` claim should say what its bearer may do here
  and nothing else. Each client now names the roles it needs, and `KeycloakRealmIT` reads the
  scope back from a running Keycloak rather than from the file.
- **A refusal Spring MVC raised at the gateway keeps its own status.** `POST /bff/me` was
  answered 500 `INTERNAL_ERROR` and logged at ERROR, because the relay advice's catch-all claimed
  it before anything read the status the exception already carried. A framework refusal is now
  answered at that status — 405 `METHOD_NOT_ALLOWED` or 406 `NOT_ACCEPTABLE`, the only two the
  gateway's one handler method can provoke — and logged at WARN, so only a true 5xx is an ERROR.
- **An unreachable key source is an ERROR line of its own, on both resource servers.** The ledger
  and the assistant each logged it at WARN, under the same message as a refused token — so the
  one failure in which *nobody* can authenticate read like one more caller presenting a bad
  credential. Both now log it at ERROR, under a message of its own, still naming the reason
  category and nothing else.
- **Every call the gateway makes to Keycloak is bounded.** The authorization-code exchange, the
  `refresh_token` grant and the JWK-set fetch that verifies an ID token each built their own HTTP
  client from a Spring Security factory that never consults Boot's, so none of them saw
  `spring.http.clients.*` — the budget the relay has always had, and which the README and
  `.env.example` both claimed covered them too. A Keycloak that accepted the connection and then
  stopped answering would have parked a Tomcat thread for ever. All three are now built from
  Boot's own builders in `KeycloakRegistration`, and `GatewayKeycloakTimeoutIT` drives that exact
  failure against each.
- **Every POST the gateway relays to the assistant now arrives.** The relay's JDK HTTP client
  defaults to HTTP/2, so on a cleartext hop it offered an h2c upgrade — `Upgrade: h2c`, an
  `HTTP2-Settings` header and a `Connection` header naming both — on every request. Tomcat ignores
  those, so the ledger never minded; uvicorn refuses an upgrade request that also carries a body
  with 400, so pricing a disposal from the web client failed every time. The gateway now pins
  HTTP/1.1 for every outbound call it makes, and `GatewayLoginFlowIT` fails the build if an upgrade
  offer reaches an upstream again.
- **`DisposeUnits` sorts lots by acquisition date before consuming them.** `consume_fifo` takes
  them in the order it is given and documents that the ordering is the caller's responsibility,
  so a caller handing it newest-first lots got a LIFO cost basis in silence, with a plausible
  number and no error — the README called it the sharpest edge in the domain. A test now hands
  it lots in the wrong order and pins the difference between a 1000.00 cost of sale and a
  2000.00 one.
- **`AccountId`'s Javadoc no longer contradicts the rule it cites.** It claimed budgeting holds
  `AccountId` values; `contexts_do_not_depend_on_each_other` forbids budgeting from naming any
  accounting type, which is why `AccountRef` exists.
- **Two more ledger Javadocs stop describing layers as unbuilt.** `Account` said the check that an
  entry matches its account's currency awaited "the application layer that does not exist yet";
  `TransactionService` has refused a mismatch since that layer landed. `TransactionRepository`
  said the journal's append-only rule was deferred to "a schema that does not exist yet"; the
  schema's triggers have refused an `UPDATE` or a `DELETE` since ADR-024.
- **`.env.example` carries `POSTGRES_PORT`.** `docker-compose.yml` publishes the database on it
  and the README documents it, but `make setup` copies `.env.example` verbatim, so a first run got
  the default 5432 without ever seeing the knob that moves it off a port a native PostgreSQL
  already owns.

- Observability across all three codebases: JSON logs on stdout carrying a W3C trace id, OTLP
  trace export that degrades to a no-op when no collector is configured, and Prometheus metrics
  on the ledger. The assistant gained its first entry point — a composition root serving
  `/health` and `/ready` — and with it the redacting logging boundary the requirements had
  listed as missing. The web client generates the `traceparent` the backends continue, logs
  through a single boundary, and no longer blanks the page silently on a render error. A
  collector, Prometheus and Grafana run behind a `monitoring` Compose profile with the
  dashboard provisioned from files. See
  [ADR-019](docs/ADRs/ADR-019-opentelemetry-and-structured-logging.md) and
  [ADR-020](docs/ADRs/ADR-020-audit-trail-follows-persistence.md), which records that the
  append-only journal is the audit trail and that it lands with the first repository.

- Test infrastructure across all three codebases: coverage measured everywhere and gated
  nowhere, an opt-in PIT mutation profile for the ledger, property-based tests on both
  backends (a seeded generator in Java, Hypothesis run derandomised in Python), the first
  unit tests for the web client, and `contracts/money-vectors.v1.json`, which both `Money`
  implementations are checked against so a divergence fails a build. Two new architecture
  rules forbid `float` and wall-clock reads in an assistant domain module, and the import
  scanner the rules rest on is now itself tested. See
  [ADR-017](docs/ADRs/ADR-017-test-strategy.md) and
  [ADR-018](docs/ADRs/ADR-018-cross-language-money-vectors.md).

- Repository baseline: requirements, architecture and ADR documents, licence, editor and
  formatter configuration, pinned toolchains for both services, Docker Compose entry point,
  and a CI pipeline that gates build, lint, format and dependency audit.
- Web client (`frontend/web`): React 19 on Vite 8 with TypeScript, Vitest, ESLint and
  Prettier, one feature directory per user-facing bounded context.

- Domain layer for all five bounded contexts. Accounting: double-entry `Transaction` with a
  balancing invariant, five account types, a sealed lifecycle, and a derived `Balance`.
  Budgeting: periodic `Budget` with per-category limits and derived remaining. Portfolio:
  lot-based FIFO cost basis. Assistance: an answer stating a figure must cite its evidence.
  Market data: quotes and staleness against a supplied clock. `Money` and `Currency` in both
  languages.

- Planning: a sixth bounded context in the ledger, holding what the owner *intends* rather than
  what happened. `PlannedFlow` with a positive amount, a direction, a recurrence and a
  `Planned → Committed → Realised` lifecycle whose realised state is frozen; `Scenario` as a named
  strategy; `Projection` as a derived, never-stored timeline of cash and invested balances with
  shortfall detection at step boundaries; `Comparison`, which refuses strategies in different
  currencies, horizons, step cadences or names rather than aligning them approximately.

### Fixed

- `Money.times` and `Money.times_rounded` now refuse every non-finite multiplier with the
  `ValueError` their contract promises. An infinity or a signalling NaN escaped as
  `decimal.InvalidOperation` - an `ArithmeticError` a caller catching `ValueError` never
  sees. Only a quiet NaN happened to reach `Money`'s own finiteness check, which made the
  gap look closed when it was not.
- `Money.times_rounded` in the Python kernel no longer fails on a large product. Its decimal
  context precision was a fixed 60 rather than sized to the value, so multiplying two large
  amounts raised `decimal.InvalidOperation` - an `ArithmeticError`, not the `ValueError` the
  module's contract promises - where the Java kernel's unbounded `BigDecimal` succeeded. The
  constructor had already been given this fix; `times_rounded` had not. Both now share one
  sizing rule.
- Voiding a *cleared* transaction no longer accepts a date before it cleared. The guard
  compared the void date against the date the transaction occurred only, so a transaction
  cleared on the 20th could be voided on the 10th and the void predated the clearing it
  reverses. `reconcile` already guarded against its own predecessor this way.

### Changed

- Services moved under `backend/`, and both are now packaged by bounded context rather than
  by layer: `accounting` and `budgeting` in the ledger, `portfolio`, `assistance` and
  `marketdata` in the assistant. A context is one directory with its own hexagon.
- Architecture tests extended to enforce context isolation in all three codebases; each rule
  was verified by making it fail.
- The ledger's ArchUnit rules are no longer dormant: `archRule.failOnEmptyShould=false` was
  deleted with the first domain class, and all five now run against real code.

[Unreleased]: https://github.com/NaimElijah/FinAssist/commits/main
