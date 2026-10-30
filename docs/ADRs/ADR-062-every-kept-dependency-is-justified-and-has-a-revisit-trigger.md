# ADR-062: Every kept third-party dependency is justified, and each has a revisit trigger

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-045](ADR-045-dependabot-triage-and-toolchain-holds.md), which triages the updates and
holds the toolchain lines, and says nothing about whether a dependency should be there.
It extends [ADR-029](ADR-029-web-client-stack.md), which held the dependencies it added to a bar
and left the rest of the web client's tree unjustified, and
[ADR-003](ADR-003-python-fastapi-for-assistant-and-analysis.md), whose reasons for Python were an
SDK and a numerical library that the assistant never imported. It extends
[ADR-002](ADR-002-java-spring-boot-for-the-ledger.md), which chose the ledger's stack and did not
say how its versions are locked, and [ADR-017](ADR-017-test-strategy.md), which chose the coverage,
mutation and property-test tools and recorded neither a licence nor a trigger for them.

## Context

**The owner's dependency rule has a record for the dependencies an ADR chose, and none for the
rest.** Every third-party dependency needs a justification: what it replaces, whether it is
maintained, its licence, and why the standard library or an existing dependency is not enough.
ADR-029 applied that bar to the web client's routing, server-state, contract-type and
interaction-test libraries; ADR-019, ADR-024, ADR-030 and ADR-032 did for the libraries they chose.
The Java test starters and build plugins, the assistant's test and lint tools, and the web
client's ESLint plugins and type packages have no record, and several runtime dependencies were not
used.

**Scan 4 vetted every direct dependency of the four manifests on 2026-10-30**
([#341](https://github.com/NaimElijah/FinAssist/issues/341)): `backend/ledger/pom.xml`,
`backend/gateway/pom.xml`, `backend/assistant/pyproject.toml` and `frontend/web/package.json`. It
read the registries, OSV, OpenSSF Scorecard, the lockfiles and the resolved Maven trees. It found:

- **The assistant declared dependencies it does not use.** `anthropic` and `pandas` were runtime
  dependencies that nothing imports, and `backend/assistant/Dockerfile` installs the runtime set
  with `uv sync --locked --no-dev`, so both shipped in the image. `uvicorn[standard]` installed five
  more packages than plain `uvicorn`, and the server runs `h11` with WebSockets off (ADR-058), so
  none of them is reachable. `httpx` was declared as a runtime dependency, though the code has no
  HTTP client and only tests import it.
- **The Java services are locked by one version number.** Neither has a Maven lockfile, and no CI
  job audits their resolved trees ([#66](https://github.com/NaimElijah/FinAssist/issues/66),
  [#303](https://github.com/NaimElijah/FinAssist/issues/303)). The Spring Boot parent version
  decides what almost everything resolves to. One version, palantir-java-format, sits inside a
  plugin's configuration, and no open Dependabot pull request named it, though a newer release
  existed.
- **The Boot release both services move to manages an OpenTelemetry release with an
  advisory.** The Boot 4.1.2 BOM manages `opentelemetry` at 1.62.0, and GHSA-ccrj-gw8r-854m
  (moderate) says that release parses the W3C `tracestate` header in quadratic time, fixed in
  1.67.0. Both Java services depend on the starter that brings it. The owner approved Boot
  4.1.2 with Spring Security 7.1.2 on 2026-10-30, as ADR-045 requires of a change to Spring
  Security.
- **The web client had a bump that could not install.** Dependabot proposed `react-dom` and
  `@types/react-dom` alone (#325). Each peers on the same minor of `react` and `@types/react`, so
  `npm ci` fails with ERESOLVE. `@types/node` was at 26.6.2, and the runtime is Node 24
  (`.nvmrc`, CI's `setup-node` and `engines`).
- **Most tools had no record of their licence or of when to drop them.** Hypothesis, certifi,
  `pathspec` and `lightningcss` are MPL-2.0, JaCoCo is EPL-2.0, and four Java transitives are
  dual-licensed.

**`LICENSE` is proprietary, "All rights reserved", on a public repository.** No record said which
dependency licences are compatible with that, or what a copyleft licence in a shipped artefact
would change.

## Decision

**Every direct dependency in the four manifests is justified in this record or in the earlier
record it names, and each row ends with the event that reopens it.** A row says what the dependency
replaces and why the standard library or an existing dependency is not enough, and gives its
licence. Maintenance was checked on 2026-10-30 from release dates and Scorecards, and the trigger
column holds the conditions that would end the justification: an archive, a missing release, a
licence change. A dependency that an earlier record justifies and this one does not repeat reopens
on the general triggers: an archive or deprecation notice, a licence change to a non-permissive
one, an advisory with no fixed release, or the standard library gaining the feature. A dependency
added later is justified in the ADR or pull request that adds it, as the owner's rule already
requires.

### The ledger (`backend/ledger/pom.xml`)

Justified by earlier records, and not repeated here:

- `spring-boot-starter-parent` (ADR-002, ADR-045).
- `spring-boot-starter-webmvc` ([ADR-002](ADR-002-java-spring-boot-for-the-ledger.md),
  [ADR-028](ADR-028-http-wire-conventions.md)) and `spring-boot-starter-actuator` (ADR-002,
  [ADR-019](ADR-019-opentelemetry-and-structured-logging.md),
  [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)).
- `spring-boot-starter-security-oauth2-resource-server` (ADR-030,
  [ADR-032](ADR-032-role-based-authorization-per-service.md),
  [ADR-037](ADR-037-an-unusable-key-set-is-an-outage.md)).
- `spring-boot-starter-opentelemetry` and `micrometer-registry-prometheus` (ADR-019).
- `spring-boot-starter-jdbc` and `postgresql`
  ([ADR-006](ADR-006-postgresql-for-the-ledger-store.md),
  [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)).
- `spring-boot-starter-test` and `pitest-maven` (ADR-017).
- `archunit-junit5` (ADR-002, [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md),
  [ADR-046](ADR-046-architecture-rules-read-the-code-never-load-it.md)).
- `testcontainers-postgresql` and `testcontainers-junit-jupiter`
  ([ADR-025](ADR-025-integration-tests-on-testcontainers.md)).

| Dependency | Why | Licence | Revisit when |
|---|---|---|---|
| `spring-boot-starter-validation` | Bean Validation on the web DTOs, in place of hand-written request checks; 24 main files use `jakarta.validation` or `@Valid` | Apache-2.0 (Hibernate Validator too) | Validation moves into the domain only, or Boot drops the starter |
| `springdoc-openapi-starter-webmvc-api` | Generates `contracts/ledger-v1.openapi.json` from the handlers, in place of a document kept by hand that would drift from them; the API starter alone, so no UI is served (ADR-028) | Apache-2.0 | Boot ships OpenAPI generation, or springdoc goes six months with no release |
| `spring-boot-flyway`, `flyway-database-postgresql` | Flyway is ADR-024's choice. Boot 4 splits autoconfiguration into per-technology modules, so the bare library migrates nothing at start-up, and Flyway ships each database's support separately | Apache-2.0 | Boot manages Flyway 13, or Flyway moves PostgreSQL support again |
| `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-starter-opentelemetry-test` | The slice and tracing test support the ledger's tests use: `@WebMvcTest`, `SecurityMockMvcRequestPostProcessors.jwt()` behind `WebSliceSecurity`, and `@AutoConfigureTracing` in `LedgerObservabilityIT`. The JDK has nothing like them | Apache-2.0 | The tests stop using slices or the tracing support (ADR-017) |
| `spring-boot-testcontainers` | Wires the Testcontainers PostgreSQL into the Spring context; ADR-025 names Testcontainers and not this module | Apache-2.0 | ADR-025 changes |
| `spring-boot-maven-plugin` | Builds the runnable jar the image copies (ADR-054) | Apache-2.0 | Packaging moves to buildpacks or another packager |
| `spotless-maven-plugin` with palantir-java-format | The authoritative formatter, gating CI (ADR-002); palantir-java-format is bumped by hand (below) | Apache-2.0 | Spotless drops palantir-java-format, or it stops tracking the JDK |
| `jacoco-maven-plugin` | Coverage as a diagnostic, never a gate (ADR-017); a build tool and test agent, absent from the packaged jar | EPL-2.0 | Coverage is dropped, JaCoCo lags a new JDK, or it enters a shipped artefact |
| `maven-surefire-plugin`, `maven-failsafe-plugin` | The split between unit tests and `*IT` classes (ADR-025); Boot manages both | Apache-2.0 | The test runner changes |
| `pitest-junit5-plugin` | PIT's JUnit 5 support for the opt-in `mutation` profile (ADR-017). Its last release was 2025-05-20, and the profile is not part of the packaged artefact or of CI | Apache-2.0 | `pitest-maven` drops support for it, or a JUnit Platform 6 incompatibility appears |

### The gateway (`backend/gateway/pom.xml`)

Justified by earlier records, and not repeated here:

- `spring-boot-starter-security-oauth2-client` (ADR-030,
  [ADR-031](ADR-031-keycloak-as-the-identity-provider.md), ADR-032).
- `spring-boot-starter-actuator` and `spring-boot-starter-opentelemetry` (ADR-019, ADR-030).
- `spring-boot-starter-test` (ADR-017).

The gateway also declares `spring-boot-maven-plugin`, Spotless with palantir-java-format, JaCoCo,
surefire, failsafe and the `webmvc-test` and `security-test` starters, on the terms of the ledger's
rows above.

| Dependency | Why | Licence | Revisit when |
|---|---|---|---|
| `spring-cloud-dependencies` and `spring-cloud-starter-gateway-server-webmvc` | The relay routes and `TokenRelay` on the servlet stack (ADR-030). The BOM pins the 2025.1 train, which spring.io lists as supporting Boot 4.1 | Apache-2.0 | The 2025.1 train reaches end of support, or Boot leaves it unsupported |
| `springdoc-openapi-starter-webmvc-api` | Generates `contracts/gateway-v1.openapi.json` from the code (ADR-030); it documents `GET /bff/me`, `POST /bff/logout` and the problem schema | Apache-2.0 | As the ledger's row |
| `org.yaml:snakeyaml` (runtime) | Boot's YAML loader reads `application.yml` with it, and `KeycloakRealmTest` parses the realm file and `docker-compose.yml` with it. It once arrived only transitively, and a test-scoped declaration left it out of the packaged jar ([ADR-040](ADR-040-the-smoke-check-trigger-fired.md), #233). Boot manages 2.6, and the gateway does not override it | Apache-2.0 | Boot stops providing it, or manages a version with an advisory |
| `org.testcontainers:testcontainers` (test) | ADR-025's tool, used for a real Keycloak and a plain PostgreSQL in `KeycloakRealmIT`; the core artefact alone, with the container lifecycle managed by hand | MIT | No release for 12 months, or CI loses Docker |

### The assistant (`backend/assistant/pyproject.toml`)

Justified by earlier records, and not repeated here:

- `fastapi` and `pydantic` (ADR-003).
- `structlog`, `opentelemetry-sdk`, `opentelemetry-exporter-otlp-proto-http` and
  `opentelemetry-instrumentation-fastapi` (ADR-019). The instrumentation package is a beta on its
  own 0.x line by design.
- `PyJWT[crypto]` (ADR-032).
- `pytest-cov` (ADR-017).

| Dependency | Why | Licence | Revisit when |
|---|---|---|---|
| `uvicorn`, plain | The ASGI server (ADR-003). It runs `h11` with WebSockets off (ADR-058), so the `[standard]` extra's five packages were never reached | BSD-3-Clause | A WebSocket route is added, or `uvloop` is wanted with a measured gain |
| `pydantic-settings` | Typed, validated environment settings in `adapters/settings.py`; `os.environ` gives unvalidated strings. It is Pydantic's own settings loader (ADR-003) | MIT | The settings stop being Pydantic models |
| `httpx` (dev extra) | Starlette's `TestClient` and the client of the real-server tests. The standard library's `urllib` is not a test client for an ASGI app. No release since 2024-12-06 | BSD-3-Clause | Starlette drops the `httpx` fallback, or the ledger client lands and an ADR chooses `httpx` or `httpx2` |
| `pytest` | The runner for the assistant and for the operator tools' suites under `ops/`, which run on the assistant's toolchain (ADR-052) | MIT | It is archived |
| `hypothesis` | Property tests of the `Money` and portfolio cores, derandomised in `tests/conftest.py` (ADR-017); the standard library has no such tool | MPL-2.0 | Its licence changes, or the tests ship in an artefact |
| `ruff`, `mypy` | The linter and formatter, and the strict type checker, that ADR-003 chose; they also check the operator tools | MIT | Either is archived or replaced |
| `pip-audit` | The assistant's CI dependency audit, `make audit-assistant` | Apache-2.0 | No release by 2027-03, or OSV-Scanner replaces it everywhere ([#66](https://github.com/NaimElijah/FinAssist/issues/66)) |
| `hatchling` (build backend) | Builds the wheel from `src/finassist_assistant`; a pure-Python backend, held by its `==` pin because it is not in `uv.lock` | MIT | `uv_build` becomes uv's default build backend |

### The web client (`frontend/web/package.json`)

Justified by earlier records, and not repeated here:

- `react`, `react-dom`, `vite`, `vitest`, `eslint`, `typescript-eslint` and `typescript` (ADR-009).
  TypeScript stays at 6.0.3 until `typescript-eslint` supports 7.
- `react-router`, `@tanstack/react-query`, `@hey-api/openapi-ts`, `@testing-library/react`,
  `@testing-library/user-event` and `jsdom` (ADR-029). ADR-029 names `user-event` 14.6.7; the pin
  has since moved to 14.6.8, and the justification stands.
- `@vitest/coverage-v8` (ADR-017).

| Dependency | Why | Licence | Revisit when |
|---|---|---|---|
| `@eslint/js`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh` | The rule sets ADR-009's ESLint needs: its recommended rules, the rules of hooks, and Fast Refresh safety | MIT | One loses its maintainers, or flat config changes the plugin API |
| `@vitejs/plugin-react` | The JSX transform and Fast Refresh for Vite, which does not transform React on its own; maintained in the Vite organisation | MIT | Vite builds in a React transform |
| `@types/node`, `@types/react`, `@types/react-dom` | Type declarations that Node, React and React DOM do not ship | MIT | A package ships its own types |
| `prettier` | The authoritative formatter ADR-009 chose; one formatter, checked in CI | MIT | Another formatter replaces it |
| `@testing-library/dom` | A required peer of `@testing-library/react` 16; the client never imports it | MIT | `@testing-library/react` stops requiring it |
| `js-yaml` override (4.3.2) | `@hey-api/json-schema-ref-parser` declares js-yaml 4.2.0 exactly, which carries three high advisories (GHSA-52cp-r559-cp3m, GHSA-5p4m-2wfm-xmqj, GHSA-2883-xcg3-v3hh) that fail `npm audit --audit-level=high` | MIT | `@hey-api/openapi-ts` allows js-yaml 4.3.2 or later |
| `lightningcss` (transitive, with 11 platform packages) | Reached through Vite 8; dev only. Recorded so a move into the shipped bundle reopens the licence question | MPL-2.0 | It enters the shipped bundle |

### The licence position

**Every package that ships carries Apache-2.0, MIT, BSD, PSF-2.0, EPL-2.0 or MPL-2.0, or a dual
licence from which one of those is taken.** None of them imposes a licence on FinAssist's own
files.

- **Dual licences in the Java services.** logback (EPL-2.0 or LGPL-2.1-only) and
  `jakarta.annotation-api` (EPL-2.0 or GPL-2.0 with the Classpath Exception) ship in the jar and
  are taken under EPL-2.0, unmodified. JNA (LGPL-2.1-or-later or Apache-2.0) is test scope only,
  taken under Apache-2.0. HdrHistogram (CC0 or BSD-2-Clause) ships, taken under BSD-2-Clause.
- **MPL-2.0.** certifi ships in the assistant's image, through `requests`. Hypothesis, `pathspec`
  and `lightningcss` with its platform packages are dev-only. MPL-2.0 binds modified files of the
  package itself, and these are installed unmodified from a registry.
- **Other permissive licences that ship.** protobuf-java (BSD-3-Clause) in both Java services,
  Bouncy Castle's `bcprov` (its own MIT-style licence) in the gateway, and `typing-extensions`
  (PSF-2.0) in the assistant's image. The web client ships seven packages, all MIT.
- **Dev-only licences outside that list.** In the web client's lock, `caniuse-lite` is CC-BY-4.0
  and `argparse` Python-2.0, alongside ISC, BlueOak-1.0.0, MIT-0 and CC0-1.0 packages. None reaches
  the shipped bundle.
- **Nothing found is GPL, AGPL, LGPL-only, SSPL, BSL or unlicensed** in what the scan read. That
  is every direct dependency, the transitives named above, every locked Python package (PyPI
  metadata; `mypy-extensions`, dev-only, leaves that field empty and states MIT in its wheel) and
  every locked npm package. It is not every Java jar: about 165 of the ledger's 186 resolved jars
  went unread. Licences come from package metadata, and the repository `LICENSE` files were
  compared for the ledger's direct dependencies only.

**Revisit when** a dependency's licence changes, a dev-only MPL or CC package moves into a shipped
artefact, or `LICENSE` stops being proprietary.

### What this scan removed, and why

- **`anthropic` and `pandas` leave `pyproject.toml`**, because nothing imports either. With the
  next two items, this takes the assistant's runtime set from 61 packages to 41; `numpy` and
  `jiter`, which have native code, are among the 20 that leave. `ANTHROPIC_API_KEY` stays as a
  setting and a redaction rule, which need no SDK. **Re-add the SDK, at its then-current
  version, in the commit that adds the `LlmPort` adapter**, which waits on
  [#40](https://github.com/NaimElijah/FinAssist/issues/40). `pandas`
  computes in binary floating point by default, which the money rule forbids, so the cores use
  `Decimal`; a numerical need they cannot meet is a decision of its own. **Both stay on the
  architecture test's forbidden list**, named explicitly in `FORBIDDEN_FRAMEWORKS`, because that
  set is derived from the declared dependencies and removing a dependency would otherwise narrow
  the rule without a failure. The test
  `Given_the_undeclared_llm_and_dataframe_libraries_When_listed_Then_both_are_forbidden` pins it.
- **`uvicorn[standard]` becomes `uvicorn`.** `h11` and `ws="none"` leave `httptools`, `websockets`,
  `watchfiles` (reload only), `uvloop` and `pyyaml` unreachable. uvicorn now runs on the asyncio
  event loop in place of `uvloop`; the effect was not measured.
- **`httpx` moves to the dev extra.** It stays on the forbidden list for the reason above, so an
  inward module still cannot open a connection through it.
  `Given_the_test_only_http_client_When_listed_Then_it_is_forbidden` pins it. The ledger client,
  when it lands, chooses `httpx` or `httpx2` in an ADR of its own. Starlette 1.6.0's `TestClient`
  prefers `httpx2` and warns on `httpx` with a `StarletteDeprecationWarning`. The warning is
  accepted, and the first trigger in the `httpx` row is where it ends.

### Version policy

- **The Spring Boot parent version is the Java services' effective lock.** Boot's BOM fixes every
  version it manages. The ledger pins by exact property only what the BOM does not manage:
  springdoc, ArchUnit, Spotless, palantir-java-format, JaCoCo, PIT and PIT's JUnit 5 plugin. The
  gateway pins the Spring Cloud train, springdoc, Spotless, palantir-java-format and JaCoCo.
  **No Maven lockfile was adopted** (a rejection, below). **Revisit when** a dependency outside
  Boot's BOM is pinned by range, or #66 or #303 makes a resolved tree available to audit.
- **palantir-java-format is bumped by hand, in both poms together.** Its version is a parameter of
  the Spotless plugin and not a dependency declaration, and no open Dependabot pull request named
  it while it stood six minor releases behind. A newer formatter can reformat files, so the bump
  runs `spotless:apply` and the diff is read.
- **Temurin and Python patch releases arrive through Dependabot's `docker` entries, and the
  Compose one-shots' Python tag is bumped by hand.** `auth-smoke` and `secret-check` name the same
  `python` tag as the assistant's Dockerfile, and Dependabot does not read Compose image tags, so
  the tag changes in the same commit as the Dockerfile.
- **`@types/node` tracks the runtime's major, Node 24.** `.github/dependabot.yml` ignores 25 and
  later; the entry goes in the change that moves the runtime.
- **The React packages move together.** `react`, `react-dom`, `@types/react` and
  `@types/react-dom` are one Dependabot group, so a pull request cannot bump one and break
  `npm ci`.
- **The `js-yaml` 4.3.2 override stays** until `@hey-api/openapi-ts` allows 4.3.2 or later.
- **The gateway writes no forwarded header, in Gateway Server MVC 5.0.3 or 5.0.4.** The
  `X-Forwarded-*` and `Forwarded` filters are registered only when
  `spring.cloud.gateway.server.webmvc.trusted-proxies` is set, and FinAssist never sets it, so
  only the filters that remove browser-supplied forwarded headers run, with identical bytecode in
  both releases. 5.0.4 changed the trusted-proxies path alone: for an untrusted peer it strips the
  `X-Forwarded-*` set and then writes it. The ledger configures no forwarded-header handling, and
  the assistant runs `proxy_headers=False`. **Revisit when** `trusted-proxies` is set.
- **PyJWT 2.15.1 is held for the owner first, as ADR-045 requires.** OSV lists no advisory against
  2.15.0, the pinned release.
- **Some dependencies have newer releases and no Dependabot pull request.** Each of the npm, uv and
  Maven entries already had five open pull requests, the `open-pull-requests-limit`, so the rest
  arrive once this scan's pull requests are closed.
- **OpenTelemetry is the one Boot-managed version the Java services override.**
  `opentelemetry.version` is 1.67.0 in both poms, in place of the 1.62.0 that Boot 4.1.2
  manages, because 1.62.0 parses the W3C `tracestate` header in quadratic time
  (GHSA-ccrj-gw8r-854m, fixed in 1.67.0). Boot's `opentelemetry-bom` import reads the
  property, so the `io.opentelemetry` API, SDK and exporter artefacts resolve to 1.67.0
  together. **Delete the property once Boot manages 1.67.0 or later.** Until then every
  Boot bump reads `opentelemetry.version` in `spring-boot-dependencies`, because an
  override lower than Boot's own would downgrade it.

## Consequences

**Makes easy**

- **A maintainer finds why a dependency is there.** What it replaces, its licence and the event
  that ends it are in one row, or one named record.
- **The assistant's image carries what runs.** The runtime set is 41 packages, down from 61, and
  an advisory in a package nothing uses can no longer reach the triage.
- **A React bump installs.** One grouped pull request carries the four packages.
- **The licence question has an answer before an image or the source is distributed.** The
  position, its exceptions and what it did not read are written down.
- **The `tracestate` advisory is out of both trees.** The override resolves to one
  consistent 1.67.0 set, and a comment in each pom names its removal.

**Makes hard**

- **Nothing fails when a trigger fires.** The rows are prose in an append-only record. Someone
  reads them at the weekly triage (ADR-045), which is practice and not enforcement
  ([#134](https://github.com/NaimElijah/FinAssist/issues/134)).
- **Two bumps have no pull request to remind anyone:** palantir-java-format and the Compose
  one-shots' Python image.
- **The Java services have no audit job.** An advisory in a Boot-managed jar reaches the owner
  through Dependabot alerts ([ADR-043](ADR-043-github-secret-scanning-and-dependabot-alerts.md)) or
  a scan, and not through a red pipeline, until #66 lands.
- **The licence position rests on metadata and on a partial read.** It says what the scan found,
  not that no other licence exists in the Java jars it did not read.
- **The Java services run OpenTelemetry 1.67.0 under a Boot that manages 1.62.0.** The
  override is the one place they leave Boot's managed set, and a Boot release that
  manages a newer version would be silently downgraded by it. Every Boot bump reads the
  managed version for that reason.
- **The assistant's test output carries a `StarletteDeprecationWarning`** until the `httpx` row's
  trigger fires, and uvicorn's change of event loop is unmeasured.
- **Each row is a claim to keep true.** This record is dated 2026-10-30. A dependency added later
  has to find its own record.

## Alternatives considered

- **A Maven lockfile.** Rejected: Maven has none of its own, and one made by a plugin adds a build
  dependency to pin what the Boot parent version and the exact property pins already fix.
  **Revisit when** a dependency outside Boot's BOM is pinned by range, or #66 or #303 makes a
  resolved tree available to audit.
- **Keeping `anthropic` and `pandas` "for later".** Rejected: they were dead code in the image,
  thirteen packages that nothing runs, and `anthropic` was a major version behind, so Dependabot
  would have proposed a breaking bump to code that does not exist. **Revisit when** the `LlmPort`
  adapter is written (`anthropic`), or when a numerical need that `Decimal` and the standard
  library cannot meet is decided in an ADR (`pandas`).
- **Accepting GHSA-ccrj-gw8r-854m and keeping Boot's 1.62.0.** Rejected: the override is one
  property per pom and resolves to a consistent tree, and the fix is a library release,
  not a change to FinAssist's code. **Revisit when** a Boot release manages 1.67.0 or
  later, which also removes the override, or when 1.67.0 stops working with the tracing
  bridge Boot manages.
- **Overriding any other Boot-managed version.** Rejected:
  Boot is the lock, and each override leaves the combination Boot tested. snakeyaml 2.7 exists
  while Boot manages 2.6, with no advisory against 2.6, and the gateway keeps Boot's.
  **Revisit when** a managed version has an advisory that the next Boot release does not fix.
- **An automated licence check in CI.** Out of scope here: it belongs with the audit work in #66
  and with scan 5 ([#342](https://github.com/NaimElijah/FinAssist/issues/342)). **Revisit when**
  either lands.

## Revisit when

- **Boot manages OpenTelemetry 1.67.0 or later.** The override is deleted from both poms.
- **The ledger client is written.** It chooses `httpx` or `httpx2`, and the `httpx` row ends.
- **The `LlmPort` adapter is written.** The SDK is declared in the same commit.
- **A dependency outside Boot's BOM is pinned by range, or #66 or #303 lands.** The lockfile
  question reopens.
- **`LICENSE` changes, or a dependency's licence does.** The licence position is rewritten.

## What lands with this record

- **`backend/assistant`.** `pyproject.toml` and `uv.lock` drop `anthropic` and `pandas`, declare
  plain `uvicorn`, and hold `httpx` in the `dev` extra. `tests/test_architecture.py` names
  `anthropic`, `pandas` and `httpx` in the explicit forbidden set, with the two tests above.
- **`frontend/web`.** `package.json` and the lockfile move the React packages together and hold
  `@types/node` on the 24 line.
- **`.github/dependabot.yml`.** A `react` group, and an `ignore` for `@types/node` 25 and later.
- **`backend/ledger` and `backend/gateway`.** Both poms move to Spring Boot 4.1.2, with
  Spring Security 7.1.2, and set `opentelemetry.version` to 1.67.0. The gateway moves to
  Spring Cloud 2025.1.4.
- **Status lines and README.** ADR-045, ADR-029, ADR-003, ADR-002 and ADR-017 read `extended by
  ADR-062`, each with its row in `docs/ADRs/README.md`, a row for 062 and a note.
