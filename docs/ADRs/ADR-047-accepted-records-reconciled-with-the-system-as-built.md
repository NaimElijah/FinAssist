# ADR-047: Accepted records reconciled with the system as built

- **Status:** Accepted
- **Date:** 2026-10-29

Corrects nine Accepted records (ADR-001, 002, 005, 010, 019, 030, 031, 032 and 035) and extends
three (ADR-025, 029 and 045), in twelve passages. None of these passages is a new design. Each
states what the system now does where the record says something else, or something less. Items 9
and 10 are the only ones that decide anything, and each says so. Item 11 extends a rule to a
module that already follows it, and item 12 states a carve-out.

## Context

**Twelve passages of Accepted records describe something other than what was built**
([#339](https://github.com/NaimElijah/FinAssist/issues/339), the architecture-reviewer scan at
`1d4f7ba`).

- **Most were true when written and stopped being true.** ADR-001 says two deployables, and the
  gateway came after it. ADR-002 pins a Maven version that two later bumps moved.
- **Some were never true.** ADR-010 names a `Period` in `shared` that no commit ever held.
  ADR-005 promises an install line the README never gave. ADR-031 lists client scopes that the
  realm file never declared.
- **One is false in a way that matters.** ADR-035 says order within a context is untouched. It is
  not, and a migration released out of order builds two databases differently from the same
  scripts.

**Records are append-only, so the fix cannot be an edit.** The repository corrects an Accepted
record with a later one and a line in its Status, as ADR-035 did for ADR-033. One record rather
than twelve, because each correction is small and one review found them together. Each item below
names the passage, what holds now, and what enforces it.

## Decision

**Each passage below is amended to what was built.** The Status line of each record named gets a
pointer here, and its body stays as written.

### 1. `/error` is not a public path

- **Passage.** ADR-030 (line 283) and ADR-032 (line 85) list `/error` among the ledger's paths that
  need no token.
- **What holds now.** The ledger permits `DispatcherType.ERROR`, not the path. The container's own
  forward after a genuine failure is let through, and a direct `GET /error` arrives as
  `DispatcherType.REQUEST`, matches no rule and is refused 401. Boot 4.1.1 ships no
  `ErrorPageSecurityFilter` and `filterErrorDispatch` defaults to `true`, so permitting nothing
  would answer a real 500 with a 403 that hides it. The gateway's chain does the same.
  (`26505db`, 2026-09-21.)
- **What enforces it.**
  `LedgerAuthorizationIT.Given_no_token_When_a_direct_error_request_is_made_Then_it_is_401_with_a_problem_body`
  and `GatewaySecurityTest.Given_no_session_When_the_error_path_is_requested_directly_Then_it_is_refused`.

### 2. The realm declares no client scopes

- **Passage.** ADR-031 (lines 77-81) lists client scopes, one mapping roles to a flat `roles` claim
  and the others adding the audiences, and ADR-030 (line 275) says "the gateway's client scope adds"
  both audiences.
- **What holds now.** The realm has no top-level `clientScopes`. The same three effects are
  protocol mappers on each client in `ops/keycloak/finassist-realm.json`:
  - `finassist-roles` on both clients;
  - `finassist-ledger-audience` on both;
  - `finassist-assistant-audience` on the gateway client alone.

  Declaring a client scope drops Keycloak's default scopes, `basic` included, and `basic` carries
  `sub`. Each client keeps `basic` in its own `defaultClientScopes`. ADR-036's Context already
  describes the mapper, and needs no amendment.
- **What enforces it.** `KeycloakRealmTest`:
  `Given_the_realm_file_When_read_Then_no_top_level_client_scope_is_declared`,
  `Given_both_clients_When_read_Then_basic_is_a_default_scope`,
  `Given_the_gateway_client_When_read_Then_it_carries_exactly_the_ledger_and_assistant_audience`
  and `Given_the_assistant_client_When_read_Then_it_carries_only_the_ledger_audience`.
  `KeycloakRealmIT` reads the result back from a running Keycloak.

### 3. The gateway answers no `NOT_FOUND`

- **Passage.** ADR-030 (lines 343-345): the gateway uses `NOT_FOUND` "for an unknown route".
- **What holds now.** `GatewayProblems` declares no such code (`e5ca598`, 2026-09-22). The chain
  ends in `anyRequest().denyAll()`, so an unknown path is refused 401 `UNAUTHENTICATED`, or 403
  `FORBIDDEN` with a session, before Spring Cloud Gateway's routing could call it unmatched. The
  codes it does declare are `HOST_NOT_ALLOWED`, `UNAUTHENTICATED`, `FORBIDDEN`,
  `CSRF_TOKEN_INVALID`, `UPSTREAM_UNAVAILABLE`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE` and
  `INTERNAL_ERROR`. The last two are not in ADR-030's table either.
- **What was recorded so far.** Only ADR-030's Status sentence; the README's row for 030 omitted it
  and now matches the Status line. The other correction that sentence lists, the `key_source` log
  level, is recorded by ADR-037 (lines 43-48 and 93) and needs nothing here.
- **What enforces it.**
  `GatewaySecurityTest.Given_a_signed_in_session_When_an_unrouted_path_is_requested_Then_it_is_denied_403`.

### 4. Order within a context is not untouched

- **Passage.** ADR-035 (lines 53-54), "Order *within* a context is untouched", and (line 134) the
  guards "are review and layout, not the build".
- **What holds now.** With `spring.flyway.out-of-order` true, Flyway applies any unapplied lower
  version, in any context. A `V1_1_5` added after `V1_2` was released runs last on an upgraded
  database and first on a fresh one. Two databases then differ, built from the same scripts.
  `ReleasedMigrationsTest` now closes it in the build:
  - every released script is pinned to its SHA-256, so an edit, or a new script with no recorded
    digest, fails;
  - within a directory, each script released must carry a higher version than every earlier one;
  - a script's major version must be the one its directory owns.

  ADR-035's directory-layout guard is therefore a build check, and its schema-qualification guard
  is still `SchemaBoundariesIT`. (`cb2a422` and `1802d07`, 2026-10-28.)
- **What enforces it.** `ReleasedMigrationsTest`:
  `Given_the_migrations_on_the_classpath_When_each_is_hashed_Then_each_matches_the_digest_it_was_released_with`,
  `Given_the_released_migrations_When_read_in_release_order_Then_versions_strictly_increase_within_each_context`
  and
  `Given_the_migrations_on_the_classpath_When_their_versions_are_read_Then_each_major_is_the_one_its_context_owns`.

### 5. The Actuator exposure guard is a security chain, plus a test

- **Passage.** ADR-019 (lines 87-89) names "the existing test asserting `/actuator/env` returns
  404" as what stops the exposure list widening by accident.
- **What holds now.** That test asserts 401 without a token and 403 with one
  (`LedgerObservabilityIT`, `LedgerApplicationIT`). Since ADR-030 the deny-by-default chain refuses
  `/actuator/env` before Actuator's exposure matters, so the test no longer guards the list. The
  list is `health,info,metrics,prometheus` in `application.yml`, and the guard is a test of the
  list itself. It reads the endpoints Actuator maps onto the web (`PathMappedEndpoints`) from the
  running context, so an `exclude` or a profile file counts as well as the one key.
- **It refuses the machine's settings first.** An environment variable or a system property under
  `management.endpoints.web.exposure.` outranks `application.yml`, and so can two that only carry
  settings: `SPRING_APPLICATION_JSON` holds inline properties, and a `SPRING_CONFIG_*` variable
  points at another document. One could turn the test red with nothing shipped wrong, or leave it
  green with the file widened beneath it, so the test fails on any of them before it asserts the
  list, whatever the last two hold. Names are read as Spring's relaxed binding reads them, so the
  matching system properties are refused too. An active profile, and an `application.yml` in the
  working directory or its `config/`, can still reach the list through a document the test does
  not look for; the ledger ships no profile file.
- **The gateway has the same pin and the same refusal.** Its list is `health` alone.
  `GatewaySecurityTest` also denies `/actuator/env` and `/actuator/heapdump` whatever the list
  says, so a widened list has to defeat two layers.
- **Compose's environment is the other half.** The pins read the test JVM's environment, never
  Compose's, so an edit adding such a key to the ledger's or the gateway's `environment:` would
  pass every test. `make check-compose` fails a `MANAGEMENT_ENDPOINTS_WEB_*`,
  `SPRING_APPLICATION_JSON` or `SPRING_CONFIG_*` key in either service, under any spelling, a
  JVM-options variable (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`), and a `command`
  or `entrypoint` on either, naming the key or member and never its value (ADR-049).
- **What enforces it.**
  - The ledger:
    `LedgerObservabilityIT.Given_the_shipped_configuration_When_the_web_endpoints_are_resolved_Then_only_the_four_listed_are_exposed`.
  - The gateway:
    `GatewayApplicationIT.Given_the_shipped_configuration_When_the_web_exposed_endpoints_are_resolved_Then_health_is_the_only_one`.
  - The chain, on the ledger:
    `LedgerObservabilityIT.Given_the_prometheus_endpoint_is_exposed_When_an_unexposed_one_is_requested_Then_it_is_still_not_served`.
  - Compose's environment: `ops/compose_check/tests`, through `make check-compose`.

### 6. The ledger's basis for needing no redaction boundary

- **Passage.** ADR-019 (lines 71-73): the ledger "holds no secret".
- **What holds now.** It holds `LEDGER_DB_PASSWORD` (ADR-024), and it receives a bearer token on
  every request (ADR-030). The real basis is that the ledger logs a refused token as its reason
  category alone, and logs no credential, token or header. It has no redaction processor because
  of that rule, not because it holds no secret.
- **What enforces it.**
  `LedgerAuthorizationIT.Given_any_bad_token_When_refused_Then_the_log_never_contains_that_token`.
  The rule is the root `CLAUDE.md`'s WARN-versus-ERROR rule (ADR-037), and
  `docs/requirements/requirements.md` states it in its "Data handling" row.

### 7. `shared` never held a `Period`, and some context types are named differently

- **Passage.** ADR-010 (lines 58-60): each `shared/` holds "`Money`, `Currency`, `Period`". The
  context table (lines 28-32) also names types that do not exist as named.
- **What holds now.**
  - The ledger's `shared` holds `Currency`, `CurrencyMismatchException` and `Money`. The
    assistant's holds `currency.py` and `money.py`.
  - No `Period` was ever defined in `backend/`. Periods live in their contexts: `BudgetPeriod` in
    `budgeting`, `Horizon` in `planning`.
  - `portfolio` has no `Holding`; its domain holds `Symbol`, `Lot` and `Position`. `Valuation` is
    defined in the application layer (`portfolio/application/usecase`).
  - `assistance` has no `Question`; the use case is `AnswerQuestion`. `Evidence` is defined in the
    application layer (`assistance/application/port`). `GroundedAnswer` is in its domain.
  - `accounting`, `budgeting` and `marketdata` are as ADR-010 names them.
- **What enforces it.** Nothing pins the contents of `shared`. What holds is that `shared` imports
  no context: `LedgerArchitectureTest.contexts_do_not_depend_on_each_other` for the ledger, and
  `test_architecture.py::Given_a_shared_module_When_parsed_Then_it_imports_no_context` for the
  assistant.

### 8. FinAssist has three deployables

- **Passage.** ADR-001 (line 32): "FinAssist is two deployable services in one repository".
- **What holds now.** Three: the ledger, the assistant and the gateway, which ADR-030 added as
  `backend/gateway`. `docker-compose.yml` builds all three from `backend/`.
- **What enforces it.** `docker-compose.yml`'s `build:` entries, and ADR-030.

### 9. On Windows the raw command is the supported path (a decision)

- **Passage.** ADR-005 (lines 26-27): the README "gives the one-line install for Windows".
- **What holds now.** It never did. No commit adds a `winget`, `choco` or `scoop` line (`git log -S`
  finds none). The README's Development note says `make` is not installed on the author's Windows
  machine, and its table gives the raw command beside each target. **This record decides that the
  raw commands are the supported path on Windows, and that no install line is given.** `make` stays
  a convenience, as ADR-005 said.
- **What enforces it.** The README's Development table, and the root `CLAUDE.md`, which keeps the
  two in step.

### 10. Maven's minor and patch releases move by triage; its major moves by ADR (a decision)

- **Passage.** ADR-002 (lines 17-18): the wrapper is "pinned to Maven 3.9.6".
- **What holds now.** Both wrappers are on Maven 3.10.0
  (`backend/ledger/.mvn/wrapper/maven-wrapper.properties` and the gateway's), moved by hand from
  Dependabot's two proposals in `2a4776e` (2026-10-20). ADR-045 holds only the Temurin and Python
  lines.
- **Decision.** **A Maven 3.x minor or patch release moves by Dependabot's ordinary weekly triage
  (ADR-045). A Maven major, 4.x, moves only by ADR, as a toolchain line.** The reason it is not
  held like Temurin and Python: ADR-045 held those because a Dockerfile-only bump is built by no CI
  job. A wrapper bump is. CI runs `./mvnw` on every push (`make test-ledger`, `make test-gateway`),
  and both Dockerfiles build with it, so the green check describes the new Maven.
- **What enforces it.** An `ignore` entry in the `maven` ecosystem of `.github/dependabot.yml`:
  `dependency-name: "org.apache.maven:apache-maven"`, `versions: [">= 4"]`. That is how Dependabot
  names the wrapper, as its pull request #118 shows (`bump org.apache.maven:apache-maven from
  3.9.6 to 3.10.0`). Delete the entry in the change that moves Maven to 4.

### 11. The gateway's integration tests need Docker too

- **Passage.** ADR-025 (line 33): "Docker is required to run the ledger's suite, and its absence
  fails the build."
- **What holds now.** The same rule binds the gateway. `KeycloakRealmIT` builds
  `ops/keycloak/Dockerfile` and runs it in Testcontainers beside a PostgreSQL 17.11-trixie, the
  tag Compose's `keycloak-db` uses. It has no skip condition, so without Docker it fails.
  `backend/gateway/CLAUDE.md` and the root `CLAUDE.md` already cite ADR-025 for it. No other
  gateway test needs Docker. `GatewayPackagedJarIT` runs the jar with `java -jar`.
- **What enforces it.** `KeycloakRealmIT`, and the absence of any
  `disabledWithoutDocker` or `@DisabledIf` in `backend/gateway/src/test`.

### 12. One carve-out from "amounts stay strings"

- **Passage.** ADR-029 (lines 100-101): the browser "does no arithmetic on money at all", and
  amounts "stay strings from the wire to the screen".
- **What holds now.** One function breaks it, deliberately. `scalePoints` in
  `frontend/web/src/features/planning/components/chartScale.ts` converts an amount to a number
  (`Number(point.cash.amount)`) to compute a pixel coordinate, and nothing else does. Every figure
  a person reads, in labels, axis extremes, tables and title text, is still the server's string
  through `MoneyText`. Each chart extreme carries the ledger's own `Money`, not the number it was
  placed by. `frontend/web/CLAUDE.md` states the carve-out, and now cites this record for it.
- **What enforces it.**
  - Shown by `chartScale.test.ts`:
    `Given_points_When_scaled_Then_each_extreme_carries_the_amount_that_holds_it_and_where_it_is`.
  - Shown by `ProjectionChart.test.tsx`:
    `Given_extremes_When_charted_Then_their_labels_show_the_exact_server_strings`.
  - **The limit "only here" is held in review.** No rule fails a second `Number(amount)`.

### The twelve, with what enforces each

| # | Record and passage amended | Enforced now by |
|---|---|---|
| 1 | ADR-030:283 and ADR-032:85, `/error` listed public | `LedgerAuthorizationIT` direct-error case; `GatewaySecurityTest` error-path case |
| 2 | ADR-031:77-81 and ADR-030:275, client scopes | `KeycloakRealmTest` (no `clientScopes`, `basic`, audiences); `KeycloakRealmIT` |
| 3 | ADR-030:343-345, gateway `NOT_FOUND` | `GatewaySecurityTest` unrouted-path case |
| 4 | ADR-035:53-54 and :134, order within a context | `ReleasedMigrationsTest`, three cases |
| 5 | ADR-019:87-89, the `/actuator/env` 404 guard | `LedgerObservabilityIT` and `GatewayApplicationIT`, each over the endpoints mapped onto the web; `make check-compose` for Compose's environment |
| 6 | ADR-019:71-73, "holds no secret" | `LedgerAuthorizationIT` bad-token log case |
| 7 | ADR-010:58-60 and :28-32, `Period` and type names | Nothing pins `shared`'s contents; review |
| 8 | ADR-001:32, "two deployable services" | `docker-compose.yml` `build:` entries |
| 9 | ADR-005:26-27, the Windows install line | README Development table |
| 10 | ADR-002:17-18, Maven 3.9.6 | `.github/dependabot.yml`, the ignore for `org.apache.maven:apache-maven` `>= 4` |
| 11 | ADR-025:33, "the ledger's suite" | `KeycloakRealmIT`, with no skip condition |
| 12 | ADR-029:100-101, no money in a number | `chartScale.test.ts`; `ProjectionChart.test.tsx`; the limit is review |

## Consequences

**Makes easy**

- **An ADR no longer contradicts the code.** A reader who follows 030, 031, 032 or 035 for a
  reason is not told something false.
- **The corrections are findable.** Each record's Status line points here, and the README row says
  so.
- **Three guards are named.** Items 4, 5 and 12 each point at the test that holds the claim.

**Makes hard**

- **Twelve passages in one record.** A reader looking for one correction finds it through the
  Status line of the record it amends, then here. Each item is short enough to read alone.
- **The corrected body is still in the old record.** Append-only means ADR-030 still says
  `/error` is public until its Status line is read.
- **The exposure pins read the machine.** Item 5's tests fail when a
  `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_*`, `SPRING_APPLICATION_JSON` or `SPRING_CONFIG_*` variable, or
  a matching system property, is set, whatever it holds, so a developer who sets one has to unset
  it to run the suite.
- **Item 12's limit is not mechanical.** A second `Number(amount)` elsewhere in the web client
  passes every rule.

**Accepted cost**

- **Item 9 leaves Windows without an install line.** A contributor who wants `make` finds it
  themselves.

## Alternatives considered

- **Edit the bodies of the twelve records.** Rejected: they are append-only, and ADR-033 and
  ADR-035 set the precedent for correcting one with a later record.
- **Twelve records, one per correction.** Rejected: nine of them are one-line corrections, and a
  record whose only content is "this sentence was wrong" is a poor use of a number.
- **Correct only `docs/architecture/architecture.md`.** Rejected: it already describes the system
  as built. The defect is that the ADRs still say otherwise.
- **For item 10, hold every Maven line, as ADR-045 holds Temurin and Python.** Rejected: a wrapper
  bump is built and tested by CI on every push, so a hold buys nothing the green check does not
  already give. Reopen it if a Maven release breaks the build in a way CI did not show.

## Revisit when

- **A correction here proves to be a decision someone disputes.** Items 9 and 10 are the likeliest.
  Split it into a record of its own.
- **A Maven 4.x release is proposed.** Item 10 sends it to an ADR, and its ignore entry is deleted
  in that change.
- **Dependabot proposes a Maven 4.x wrapper despite the ignore entry.** Item 10's guard is then not
  holding, and the entry's name or syntax is fixed.
- **Another scan finds a passage that is wrong.** Append it to a new record. Do not extend this
  one.

## What lands with this record

- **Status lines and README.** Corrected by 047: ADR-001, 002, 005, 010, 019, 030, 031, 032 and
  035. Extended by 047: ADR-025, 029 and 045. ADR-030's row in the README now carries the same
  relationships as its Status line, which also names the two corrections it always did.
- **`.github/dependabot.yml`.** The `ignore` entry of item 10, with a comment naming this record.
- **`frontend/web/CLAUDE.md`.** The sentence on `scalePoints` cites this record, not ADR-029
  (item 12).
- **The tests of item 5** exist already: `LedgerObservabilityIT` and `GatewayApplicationIT`, and
  `ops/compose_check` for Compose's half.
