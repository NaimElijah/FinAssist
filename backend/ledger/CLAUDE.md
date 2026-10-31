# CLAUDE.md — ledger

Java 21, Spring Boot. The root `.claude/CLAUDE.md` binds here too; this file adds what binds the
ledger alone.

## Layout

Contexts `accounting/`, `budgeting/` and `planning/`, each with `domain/` (split into
sub-packages), `application/{port/in,port/out,service}/` and `adapter/{in/web,out/persistence}/`;
`accounting` and `planning`, the two that raise events, also have `adapter/out/event/`. Then a
top-level `shared/` kernel, the `composition/` root, and `LedgerApplication`.

Each context has an inbound HTTP adapter under `/api/v1`, behind `HostAllowListFilter` and
problem+json error handling. The OpenAPI document is generated from the code and committed at
`contracts/ledger-v1.openapi.json`, pinned by `LedgerContractIT` (ADR-004, ADR-028). The ledger
answers Actuator probes and traces its business endpoints (ADR-019).

## Commands

From `backend/ledger`:

- **Run** — `./mvnw spring-boot:run`, after `docker compose up -d db`. It applies its Flyway
  migrations at start-up, and needs Keycloak up to verify a token, though not to start (no service
  runs OIDC discovery, ADR-030).
- **Test** — `make test-ledger`: `./mvnw -B verify`. One class:
  `./mvnw -B test -Dtest=LedgerArchitectureTest`.
- **Docker must be running for `verify`.** The `*IT` tests drive the store against a real
  PostgreSQL in Testcontainers and fail rather than skip without it (ADR-025). Surefire (the unit
  suites) needs no Docker; failsafe does. `ApplicationYmlPlaceholdersTest` is a surefire test, so
  the `@Value` placeholder guard runs without Docker.
- **Lint / format** — `./mvnw -B spotless:check` / `./mvnw -B spotless:apply`. javac's own lint
  runs on every compile, main and test: `pom.xml` passes `-Xlint:all -Werror`, so any warning
  fails `test`, `verify`, `package` and the image build, while `spotless:check` alone compiles
  nothing. Fix the warning rather than excluding its category; a suppression names its category
  and says why on the line above. An exception in the domain, the application layer or `shared`
  declares `serialVersionUID` without `@Serial`, which is `java.io`.
- **Regenerate the contract after every endpoint change** — `./mvnw -B verify -Dtest=NONE
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LedgerContractIT` (Docker running). On a
  mismatch, copy `target/contracts/ledger-v1.openapi.json` over
  `contracts/ledger-v1.openapi.json`, then run `make generate-web-api`.
- **Mutation testing** — `make mutation-ledger`: `./mvnw -B -Pmutation test-compile
  org.pitest:pitest-maven:mutationCoverage`. A diagnostic, gated on nothing (ADR-017).

## Architecture

- **The eighteen ArchUnit rules in `LedgerArchitectureTest` are live.** The
  `archRule.failOnEmptyShould=false` escape hatch was deleted with the first domain class, so a
  rule matching nothing now fails rather than passing quietly. Do not reintroduce it.
- **The domain, the application layer and `shared` import no framework and no I/O** — no Spring,
  `jakarta`, `javax`, Jackson, springdoc or swagger; no logging API (`org.slf4j`, `ch.qos.logback`,
  `org.apache.logging`, `org.apache.commons.logging`, `java.util.logging`), `io.opentelemetry`,
  `io.micrometer`, `org.flywaydb`, `com.nimbusds` or `com.zaxxer.hikari`; no `java.sql`,
  `java.net`, `java.io`, `java.nio.file` or `java.nio.channels` (the rest of `java.nio`, such as
  `StandardCharsets`, is not I/O).
  `FORBIDDEN_FRAMEWORK_PACKAGES` in `LedgerArchitectureTest` is the list; logging and metrics
  belong in adapters and `composition`.
- **A translation reaches the upstream context through its in-ports only.** A `composition` class
  implementing a consumer's out-port (`AccountingSpendJournal`, `AccountingOpeningPositions`) may
  name no `application.service` class, no adapter, and no out-port interface it does not implement;
  `translations_reach_the_upstream_context_only_through_its_in_ports` fails the build otherwise.
  The `port.out` records it translates into stay allowed. Because a lambda `@Bean` or a helper
  class implements no out-port, `composition_calls_no_out_port_service_or_adapter` adds that no
  `composition` class may call, or take a reference to, a method of an out-port interface, an
  `application.service` class or an adapter: `LedgerConfiguration` constructs them, never calls
  them. A decorator in `composition` delegates through the in-port it decorates. Converting the
  upstream's exceptions at the boundary is pinned by `AccountingOpeningPositionsTest`; a
  translation that drops what the in-port returns is held in review.
- **There is no entity type at all.** Persistence lives in `adapter/out/persistence` and maps rows
  to aggregates by hand — the strongest form of "an entity is not an aggregate".
- **The `Money` mapping is duplicated per context on purpose.** The slice rule leaves no legal
  shared home, and ADR-021 refused to let coupling accumulate in `shared`.
- **Security lives in `composition`, never in a top-level `security` package.** The slice rule
  would treat a new context-shaped package as a bounded context and fail
  `contexts_do_not_depend_on_each_other`. `LedgerSecurityConfiguration`, `LedgerPermissions`,
  `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` all live there; a controller
  still touches only `java.security.Principal`.

## Domain and application

- **The books balance.** A `Transaction` holds two or more entries summing to zero in one
  currency, checked in the constructor, so an unbalanced one is never constructed (ADR-011).
- **A `PlannedFlow` amount is always positive and its `Direction` carries the sign** — the
  opposite of `Entry`, which is signed because double entry must balance (ADR-015).
- **Repository ports expose no delete.** A lifecycle change is stored by saving the aggregate in
  its new state: ADR-020's append-only obligation as a port shape, which the durable adapter
  inherits (ADR-021).
- **Domain events are raised by the application service.** The aggregates are records whose value
  semantics the suites rely on, so they carry no pending-events list. Only `accounting` and
  `planning` raise events — a `Budget` has no lifecycle. Nothing subscribes yet, deliberately
  (ADR-023).
- **Production keeps no event.** `DiscardingAccountingEvents` and `DiscardingPlanningEvents` accept
  each event and drop it; the recording `InMemoryAccountingEvents` and `InMemoryPlanningEvents` are
  test doubles under `src/test`, read by the service tests. The transactional outbox is deferred
  until the first consumer exists, not refused (ADR-023, ADR-048).
- **A projection is bounded where it is built** (ADR-058). `Horizon.MAXIMUM_SPAN_YEARS` (100) and
  `Comparison.MAXIMUM_SCENARIOS` (10) are checked in the constructors, because a projection
  computes and holds every step at once; the controllers answer a longer horizon 422
  `RULE_VIOLATED` and more scenarios 400. Pinned by `HorizonTest`, `ComparisonTest` and
  `ProjectionControllerTest`.
- **`@Transactional` goes on the adapter, never on an application service** —
  `the_application_layer_depends_on_no_framework` fails the build for the latter. When a use case
  must write across two aggregates, the boundary moves to a decorator in `composition`.

## Persistence

PostgreSQL, through hand-written SQL over `JdbcClient`, Flyway migrations, and a schema per
bounded context (ADR-006, ADR-024).

- **The journal is append-only in the database.** Triggers on `accounting.journal_transaction`,
  `journal_entry` and `journal_transaction_state` refuse an `UPDATE` or a `DELETE`, and `V1_3`'s
  refuses an entry for a transaction that already has a state row, so a recorded transaction's
  entries are closed: write the header, then the entries, then the opening state, as the adapter
  does. A lifecycle change is an `INSERT` whose highest `sequence` is the current state. Do not
  add an `UPDATE` to `JdbcTransactionRepository` — the database will refuse it, which is the point
  (ADR-020, ADR-024). `budgeting` and `planning` are ordinary mutable tables: ADR-020 binds the
  record of what happened, and ADR-015 puts intent in another context.
- **Every journal insert names `recorded_by`; never validate the constraints.** `V1_2` added a
  nullable `recorded_by` and a `CHECK (...) NOT VALID` on `journal_transaction` and
  `journal_transaction_state`, so a row written before it honestly stays `NULL` while every new one
  must name a non-blank subject — the token's `sub`. `JdbcTransactionRepository` writes it on every
  `INSERT`. Never run
  `ALTER TABLE ... VALIDATE CONSTRAINT` on either: it would scan the historical `NULL` rows and
  fail, and the append-only triggers forbid the `UPDATE` a fix would need (ADR-033).
- **`logServerErrorDetail=false` stays on the data source** (`application.yml`, under
  `hikari.data-source-properties`; ADR-058). pgjdbc otherwise appends the server's `Detail` line to
  an exception's message, and for a constraint violation that line quotes the failing row, which
  every logged 5xx would carry. The error and the constraint's name remain. Pinned by
  `LedgerApplicationIT`.
- **`spring.flyway.out-of-order` stays `true`; never turn it off.** One history table with a major
  version per context (ADR-024) means the next migration for any context but the highest arrives
  below a version already applied, which Flyway classes `IGNORED` and refuses to start over. Safe
  between contexts because no foreign key crosses a schema. Within a context it is not safe: with
  the flag on, a script released below one already applied runs last on an upgraded database and
  first on a fresh one, so `ReleasedMigrationsTest` fails one that does not carry a higher version
  than every earlier release in its directory (ADR-047). Checksum validation is untouched.
  `LedgerUpgradeIT` is what turns red: it rebuilds the history a real database holds
  (`1.1`, `2.1`, `3.1`) from the committed scripts, byte for byte, then starts the ledger's own
  context against it. Extend it when the applied set changes. Never reach for
  `ignoreMigrationPatterns`, which would start the ledger with the column missing (ADR-035).
- **Each context's migrations live in `src/main/resources/db/migration/<context>/`, under the
  major version that context owns** — `accounting` 1, `budgeting` 2, `planning` 3 — so the next
  accounting migration is `V1_3__<description>.sql`. `ReleasedMigrationsTest` fails a script whose
  major is not its directory's, and records each released script's SHA-256 in release order:
  append the new one at the end, above every version its context has released, and never change a
  digest once it is merged to `dev`.
- **No foreign key crosses a schema.** `SchemaBoundariesIT` fails the build if one does. It is the
  storage-layer half of `contexts_do_not_depend_on_each_other`, which reads bytecode and so stops
  at the edge of the JVM.
- **Money is `numeric` with no declared precision.** PostgreSQL preserves the exact scale it is
  given, and `Money` rejects rather than rounds excess precision. `numeric(19,2)` would break JPY,
  which has no minor units.

## HTTP

- **A controller calls in-ports only.** `inbound_adapters_reach_only_in_ports` fails the build for
  a controller reaching a repository, an outbound adapter, or a concrete `application.service`
  class; `outbound_adapters_do_not_reach_inbound` forbids the reverse.
- **Problem+json is layered** (ADR-028). A per-context `@RestControllerAdvice` runs at the highest
  precedence and maps that context's typed exceptions; `LedgerProblemHandler`, the composition
  fallback, runs at the lowest and catches what none of them claimed; a controller-local handler
  answers an id named in a body or query, since the same not-found exception is 404 from a path
  and 422 from a body.
- **A JSON number in any text member is a 400**, through `StrictTextCoercion`, proven against the
  running ledger by `AccountingHttpIT`. A `@WebMvcTest` slice that sends a number must `@Import`
  it, since a slice loads no scanned configuration.
- **A date outside the years 1 to 9999 is a 400**, in a query parameter or a body member, through
  `SupportedDates`, proven against the running ledger by `AccountingHttpIT`. A slice that sends
  one must `@Import` it, as it must `StrictTextCoercion`. Its query formatter parses every
  `LocalDate` as ISO and overrides any `@DateTimeFormat` pattern, so a non-ISO query date needs
  another way to be bounded.
- **A multipart body that cannot be parsed is a 400 `MALFORMED_REQUEST`**, not a 500: Spring MVC
  parses multipart before it chooses a route and nothing here takes it, so the failure is the
  request's (`LedgerProblemHandler`, ADR-058). Proven by `LedgerApplicationIT`.
- **A request the Spring Security firewall refuses is a 400 problem with a `code`**, through
  `ProblemRequestRejectedHandler`, never Boot's own error body, which has none and echoes the path
  (ADR-058). `LedgerSecurityConfiguration` registers it beside the handler that marks the
  observation failed. Pinned by `RequestRejectedHandlerTest` and `LedgerApplicationIT`.
- **A fractional or decimal-pointed JSON number in an integer field is a 400**, `1.5` and `1.0`
  alike (`spring.jackson.deserialization.accept-float-as-int: false`).
- **A web DTO's simple name is unique across every context**
  (`web_dto_names_are_unique_across_contexts`). `MoneyDto` alone is exempt, one identical copy per
  context on purpose; `MoneyDtoShapesTest` keeps the copies identical.
- **The contract snapshot is regenerated with every endpoint change**, or `LedgerContractIT` fails
  the build. It also refuses an `operationId` ending `_<digits>` (a springdoc collision) and a
  response schema missing `required` for an always-present member, a schema that is a JSON
  number, and a path or query parameter that carries money or a decimal, by `$ref`, pattern or
  name.

## Security

- **Every request needs a valid bearer token, and access is denied by default** (ADR-030).
  `LedgerSecurityConfiguration` builds the chain. `LedgerAuthorizationIT` checks it against an
  explicit, named inventory of every endpoint the running server answers to — plus springdoc's own
  and the container's error forward — so an endpoint added without a matching URL rule fails the
  build rather than shipping open.
- **A key-source outage is told apart where it is raised, never by an exception's message** (the
  root's WARN-versus-ERROR rule, ADR-037). `ProblemAuthenticationEntryPoint.reportOutage` logs the
  outage, and `EmptyKeySetIsAnOutage` checks for an empty set only once no key matched. Pinned by
  `ProblemAuthenticationEntryPointTest` and `LedgerKeySourceOutageIT`. `LedgerJwksBodyTest` serves
  the ledger the same key-set bodies that `tests/test_auth.py` serves the assistant: keep the two
  in step.
- **The Host allow-list lives in `composition`.** `HostAllowListFilter`, at the highest filter
  precedence, refuses a `Host` outside `LEDGER_ALLOWED_HOSTS` with 403 — before Actuator, and
  before any controller (ADR-030).
- **A request body over 64 KiB is refused 413 `CONTENT_TOO_LARGE`** by `RequestBodyLimitFilter`,
  in `composition`, before anything reads it: after the Host allow-list and Spring MVC's request
  observation, ahead of the form-content filter and Spring Security. `RequestBodyLimitIT` reads that
  order from the running server. A slice loads no filter registration, so only an IT proves it.
- **A problem written before the dispatcher is built by `Problems.write` and sent by
  `Problems.send`** (the Host allow-list, the body limit and the firewall handler), so its body has
  the shape of every controller-advice problem: `type` `about:blank`, a `code` member, absent
  members omitted. The authentication entry point and the access-denied handler build theirs from
  the same `Problems.BLANK_TYPE` and `CODE_MEMBER`. Add a new early writer to `Problems`, not
  another copy.
- **Every `@WebMvcTest` slice imports `WebSliceSecurity`.** A slice loads no scanned configuration
  (ADR-028's convention, extended to ADR-030), so without it a slice would boot with no security at
  all — every request admitted, which is a false pass, not a fast one. `WebSliceSecurityImportTest`
  reads the compiled test classes and fails the build for a slice that omits the import.
- **The packaged jar has no start-up check yet** (#234; ADR-040); the gateway's
  `GatewayPackagedJarIT` is the model.

## Dependencies

- **On every Spring Boot parent bump, read Boot's managed `opentelemetry.version`.** `pom.xml`
  overrides it to 1.67.0 for GHSA-ccrj-gw8r-854m, and an override lower than Boot's own
  downgrades it. Delete the property once Boot manages 1.67.0 or later (ADR-062).
- **The Maven distribution is SHA-256-pinned** by `distributionSha256Sum` in
  `.mvn/wrapper/maven-wrapper.properties`. Dependabot recomputes it when it bumps the wrapper; a
  hand edit of `distributionUrl` must update it too, or `mvnw` refuses to run.
