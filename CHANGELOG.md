# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries are generated from the [Conventional Commit](https://www.conventionalcommits.org/)
history; write the commit message well and this file writes itself.

## [Unreleased]

### Added

- **Every secret in `.env` can be rotated in place, without a realm re-import.** The new
  "Rotating a secret" section of `docs/operations/operations.md` covers:
  - the two database roles, changed with `\password`;
  - Keycloak's admin and the two client secrets, changed with `kcadm.sh`;
  - Grafana's admin.

  Each recipe writes `.env` first and applies the value from inside the container that holds it,
  so no value is typed on a command line. None deletes the realm's users or a volume. Each recipe,
  and the sequence that rotates them all at once, was run against an isolated Compose project and
  ended in a sign-in and a passing smoke check
  ([#140](https://github.com/NaimElijah/FinAssist/issues/140)).
- **A locked-out owner, or a lost admin password, has a way back that keeps the realm's users.**
  `docs/operations/operations.md` gains two procedures.
  - **"Restoring the owner's sign-in"**, in one `kcadm.sh` command or through the admin console:
    1. Clear the brute-force lockout.
    2. Set a temporary password.
    3. Delete the lost OTP credential.
    4. Ask for a new device again.

    Without step 4, the realm's conditional OTP would let the owner sign in with a password
    alone. The owner's id, and so the `sub` their journal rows name, is unchanged.
  - **"Recovering Keycloak's admin account"** adds a temporary admin with Keycloak's own
    `bootstrap-admin`, and `keycloak-db-data` is never touched.

  Both were run end to end in an isolated Compose project
  ([#140](https://github.com/NaimElijah/FinAssist/issues/140)).
- **A restore proves the books it put back are the ones backed up.** `make backup` records a
  digest of every account and journal row, never an amount, taken before and after the dumps,
  and it fails if the books changed in between. `make restore` and `make restore-check` then
  require the restored books to reproduce that digest. Every balance is derived from those rows,
  so a matching digest means every balance reads back unchanged, which is #137's acceptance check,
  repeatable rather than a single manual comparison
  ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).
- **`make test-backup` tests what the backup CLI does without Docker.** That covers the redaction
  every reported PostgreSQL message passes through, the manifest a restore trusts, the parsing of
  `docker compose ps`, and the guard that keeps the restore check off the owner's project. It runs
  in CI beside `make lint-backup`. Review found the redaction let through an unquoted amount or
  UUID, a quoted value split across lines, and the statement after "Command was:". It now
  withholds all of them ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).
- **`make restore-check` proves a backup restores, rather than assuming it.** It runs only as the
  Compose project `finassist-restore-check`, on ports of its own. It restores both dumps into
  fresh volumes, starts Keycloak and the ledger on them, and then requires:
  - the ledger to be ready on the restored Flyway history;
  - both `recorded_by` checks to be still `NOT VALID`;
  - every account the database holds to be listed by the ledger, and answered in its own
    currency;
  - the owner to keep an OTP credential, unchanged once Keycloak has started.

  That last requirement is what tells a restored owner from one the realm import re-created. The
  check cleans up its own containers and volumes by project label, and is never run in CI
  ([ADR-038](docs/ADRs/ADR-038-back-up-each-database-as-a-logical-dump.md),
  [#137](https://github.com/NaimElijah/FinAssist/issues/137)).
- **`make restore` puts a backup back, one database or both.** It checks each dump against its
  manifest's SHA-256, and refuses until the ledger and Keycloak are stopped and the target
  databases are running and hold no table. Under `restart: unless-stopped` either service would
  otherwise migrate or import into the empty database first. Each database is then restored in
  one transaction, keeping owners, and never needs an `UPDATE` or a `VALIDATE CONSTRAINT`.
  `DATABASE=keycloak` alone undoes a realm re-import. The README gives the recipes, including a
  PostgreSQL major-version move for each database
  ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).
- **`make backup` backs up the books and the identity store, each on its own.**
  `ops/backup/backup.py`, run with `uv run`, so it works from PowerShell too, writes a
  custom-format `pg_dump` of `db` and of `keycloak-db` into a git-ignored
  `backups/<UTC time>/`, with a manifest recording each file's size and SHA-256. It refuses to
  start a container in your project, and it fails, removing the partial folder, unless each dump
  is non-empty and reads back in full. On demand only: no recovery-point target has been set. See
  [ADR-038](docs/ADRs/ADR-038-back-up-each-database-as-a-logical-dump.md)
  ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).
- **The Compose stack restarts what crashes, and bounds its logs.** Every long-running service
  runs under `restart: unless-stopped`: a crashed service, and the whole stack after Docker
  restarts, comes back without anyone running `docker compose up`, while one stopped on purpose
  stays stopped. Each container keeps at most three 10 MB `json-file` logs, where before Docker
  kept every line for the life of the container. Both are stated once, in `x-` blocks, and kept
  off the one-shot `auth-smoke`
  ([#142](https://github.com/NaimElijah/FinAssist/issues/142)).
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

- **The quick start's one-off `pg_dump > finassist-before-upgrade.sql` is now `make backup`.**
  The old line wrote a dump of the books into the working tree, where `*.sql` is not ignored, and
  left checking it to `wc -c`. `/backups/` and `*.dump` are now git-ignored
  ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).
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

- **`consume_fifo` refuses a holding `position()` refuses, before it sells anything.** It
  consumed lots of two symbols as one instrument, with no error. Lots in two currencies were
  refused only when a sale happened to reach into the second one, so a smaller sale priced a
  mixed holding. Both functions now share one check, so a mixed holding is refused at any sale
  quantity: `ValueError` for two symbols, `CurrencyMismatchError` for two currencies
  ([#244](https://github.com/NaimElijah/FinAssist/issues/244),
  [ADR-012](docs/ADRs/ADR-012-multi-currency-without-conversion.md)).
- **The Java services no longer claim to be Apache-2.0 code by VMware.** Neither POM named a
  licence, developers, URL or SCM, so Maven took Spring Boot's own from
  `spring-boot-starter-parent`: the effective POM of the ledger and the gateway said Apache License
  2.0, developer "Spring, VMware, Inc.", and Spring Boot's repository. Both now declare the
  project's own: proprietary, all rights reserved, pointing at `LICENSE`, as the README, the
  assistant's `pyproject.toml` and the web client's `"UNLICENSED"` already did
  ([#293](https://github.com/NaimElijah/FinAssist/issues/293)).
- **`consume_fifo` sells the oldest lots first, whatever order it is given them in.** It used to
  consume lots in the order it received them, so a caller passing them newest first got a LIFO
  cost basis, and a realised gain that looked right, with no error. Only `DisposeUnits` sorted
  them first. The function now sorts them by acquisition date itself, keeping lots acquired the
  same day in the order given, so FIFO no longer depends on each caller remembering to ask for it.
  `DisposeUnits` no longer sorts
  ([ADR-041](docs/ADRs/ADR-041-consume-fifo-orders-its-own-lots.md),
  [#69](https://github.com/NaimElijah/FinAssist/issues/69)).
- **The assistant's money arithmetic no longer rounds past 28 significant digits.** `Money.plus`,
  `minus`, `negate` and `times` ran in Python's default decimal context, which keeps 28 digits and
  rounds the rest away silently. `Money` then accepted the rounded result whenever it still fitted
  the currency, so `1E+30 + 0.01` USD came back as `1E+30`, and a product needing three decimals
  was accepted instead of refused. Java's `BigDecimal` never rounds. `plus`, `minus` and `times` now
  compute in a context sized to their operands, with rounding trapped, and `negate` flips the sign
  alone, which never rounds, so the two kernels agree. Four new shared vectors pin the agreement,
  and mirrored tests cover negation, including that zero negated stays `0.00`
  ([#70](https://github.com/NaimElijah/FinAssist/issues/70)).
- **A position's quantity is totalled exactly.** `position()` summed its lots' quantities in
  Python's default 28-digit context, so a lot finer than the rest could vanish from the total held.
  It now adds them in the exact context `consume_fifo` already uses. Its cost total was fixed with
  `Money.plus`. That context, and `Money`'s, now take the widest exponent range Python allows: at
  the default's, a quantity below about `1E-1000000` made `position()` and `consume_fifo` raise
  `decimal.Inexact`, and `times` raise it where `ValueError` is promised
  ([#70](https://github.com/NaimElijah/FinAssist/issues/70)).
- **The assistant's `Money` no longer holds a negative zero.** Python's `Decimal` has one and
  Java's `BigDecimal` has none, so `-0` read from input, a negative amount times zero, and a
  negative product that rounds to zero all rendered `-0.00` in the assistant, and on the wire,
  where the ledger renders `0.00`. It compared equal to zero, so no test noticed. The constructor
  now drops the sign of a zero, which every operation passes through. Five new shared vectors pin
  the agreement, and Java passed them unchanged
  ([#239](https://github.com/NaimElijah/FinAssist/issues/239)).
- **`GatewayLoginFlowIT$LedgerDown` can no longer hand its dead ledger's port to its own gateway.**
  Its dead ledger was a port reserved and released, and the gateway the test then starts binds
  port 0 too. On CI it answered 401 where 502 was expected, with none of the warnings a failed
  sign-in, a refused refresh or an unreachable upstream log. Pointing the relay at the gateway's
  own port reproduces exactly that. The port is now held by a socket that is bound but never
  listens, so nothing else can listen where the relay connects, and a connection to it is refused.
  A new test pins that a server cannot bind it
  ([#238](https://github.com/NaimElijah/FinAssist/issues/238)).
- **The packaged gateway starts again.** A test-scoped `snakeyaml` declaration had removed it
  from the repackaged jar, by Maven's nearest-wins mediation, so Boot could not read
  `application.yml`. The gateway failed at start-up under Compose and natively, while every suite,
  run on the test classpath, stayed green. It is now runtime-scoped, and `GatewayPackagedJarIT`
  runs the jar `package` wrote with `java -jar` and requires it to start and report ready, so the
  same class of defect fails CI whichever library goes missing
  ([#233](https://github.com/NaimElijah/FinAssist/issues/233)).
- **Compose waits on the gateway's and the assistant's readiness, not their liveness.** Their
  healthchecks probed `/actuator/health` and `/health`, so `service_healthy` meant only "alive".
  They now probe `/actuator/health/readiness` and `/ready`, as the ledger's and Keycloak's already
  did, so the assistant's `/ready` turning into a real check when the ledger client lands (#62)
  changes what Compose sees
  ([#142](https://github.com/NaimElijah/FinAssist/issues/142)).
- **The ledger answers 503, not 401, when the JWKS endpoint serves a key set with no keys.**
  Spring Security parses `{"keys": []}`, or a set whose every key is of a type Nimbus skips, and
  then reports the token's key missing, so every request was refused 401 at WARN while no token
  at all could be verified, and nobody was paged. `EmptyKeySetIsAnOutage` wraps the key selector
  Spring builds and makes that case the same outage as an unreachable source: 503
  `AUTHENTICATION_UNAVAILABLE`, logged at ERROR. A set lacking only the token's key is still
  refused 401. Pinned by `LedgerJwksBodyTest`. See
  [ADR-037](docs/ADRs/ADR-037-an-unusable-key-set-is-an-outage.md)
  ([#126](https://github.com/NaimElijah/FinAssist/issues/126)).
- **The assistant answers 503 when the JWKS endpoint answers JSON that is not a usable key set.**
  PyJWT raises `PyJWKClientError` for `[]`, the type a token naming a key the set lacks raises
  too, and `PyJWKSetError` for `{}`, `{"keys": []}` or a set of only unsupported keys, which the
  verifier fell through to `rejected`. So the assistant refused every request 401 at WARN, and a
  JSON integer too long for Python to convert, a key member of the wrong JSON type, or a
  connection reset mid-response escaped as a 500. `jwks_key_source` now builds a `PyJWKClient`
  whose `fetch_data` — which fetches and parses the set and never sees a token — raises every
  failure as `KeySourceUnavailable`: 503 `AUTHENTICATION_UNAVAILABLE`, logged at
  ERROR, the ledger's answer for the same bodies. Pinned in `test_auth.py` through the real
  client and the assembled app. See
  [ADR-037](docs/ADRs/ADR-037-an-unusable-key-set-is-an-outage.md)
  ([#126](https://github.com/NaimElijah/FinAssist/issues/126)).
- **The assistant logs an `alg: none` token as `malformed` whichever key source it asks.** It
  checks a token's algorithm before it looks a key up, as the ledger does. The real `PyJWKClient`
  looks a key up by the header's `kid` first, and such a token names none, so it was logged
  `unknown_key` in production but `malformed` in every test that used a stand-in key source. The
  answer was 401 either way; only the reason in the WARN line changes
  ([#124](https://github.com/NaimElijah/FinAssist/issues/124)).
- **The gateway no longer relays a browser's `HTTP2-Settings` header.** Spring Cloud Gateway's
  hop-by-hop filter strips an incoming `Upgrade` and `Connection`, but not `HTTP2-Settings`, so a
  client-supplied one reached the ledger or the assistant as sent. Nothing upstream acted on it
  without the other two headers. Both relay routes now remove it with their own
  `RemoveRequestHeader` filter, and `GatewayLoginFlowIT` sends one through each and fails the build
  if it arrives ([#98](https://github.com/NaimElijah/FinAssist/issues/98)).
- **The assistant answers 503, not 500, when the JWKS endpoint answers a body that is not JSON.**
  PyJWT's `PyJWKClient` wraps only transport failures, so a `200` whose body was not JSON text,
  was not UTF-8, or nested brackets too deep to parse escaped `TokenVerifier` unwrapped. Any request
  whose signing key was not already cached then answered 500 `INTERNAL_ERROR`. It is now the same
  outage as an unreachable key source: 503 `AUTHENTICATION_UNAVAILABLE`, logged at ERROR, which
  is what the ledger already answered for the same body. Pinned in `test_auth.py` through the real
  client and the assembled app ([#121](https://github.com/NaimElijah/FinAssist/issues/121)).
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
  expressed the append-only rule as a port shape "rather than deferred to a schema that does not
  exist yet"; that schema exists, and its triggers have refused an `UPDATE` or a `DELETE` since
  ADR-024.
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

### Security

- **GitHub now watches the public repository.** Dependabot alerts, secret scanning and push
  protection were all off when the repository went public. All three are now on: an advisory
  against the npm or Python trees raises an alert without waiting for a push, and a push that
  holds a provider's credential is blocked, unless the pusher bypasses it with a reason, which
  GitHub records as an alert. The Java services reach the dependency graph only as
  their direct dependencies, mostly without versions, so their runtime trees stay unaudited
  ([#66](https://github.com/NaimElijah/FinAssist/issues/66)). A redacted gitleaks scan of every ref, every pull request's head included, ran first.
  It found four matches, all false positives: two JDBC URLs and two test-only fixtures.
  Dependabot security updates stay off, because they would open their pull requests against
  `main` and skip `dev`
  ([ADR-043](docs/ADRs/ADR-043-github-secret-scanning-and-dependabot-alerts.md),
  [#139](https://github.com/NaimElijah/FinAssist/issues/139)).
- **The assistant verifies tokens with PyJWT 2.15.0**, past two advisories against 2.14.0
  (`PYSEC-2026-4141`, `PYSEC-2026-4183`). Two requests that answered 500 now answer as the
  contract says: a token whose payload is nested too deeply is refused 401 `malformed`, though
  the key lookup reads it before any signature is checked, and a JWKS endpoint that hangs up
  without answering is an outage, 503 with its ERROR line. urllib3, which the OTLP exporter
  reaches through `requests`, moves from 2.7.0 to 2.8.0, past three more (`PYSEC-2026-4175` to
  `4177`). The web client's toolchain takes patched `brace-expansion`, `source-map-js` and
  `undici`; none of the three ships in the bundle.

[Unreleased]: https://github.com/NaimElijah/FinAssist/commits/main
