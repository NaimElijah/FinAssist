# Architecture Decision Records

One file per decision, `ADR-NNN-short-kebab-title.md`, numbered monotonically. These are
append-only history: to change course, add a new record and mark the old one
`Superseded by ADR-NNN`. Never rewrite an accepted one.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-polyglot-java-python-split.md) | Split into a Java ledger and a Python assistant | Accepted |
| [002](ADR-002-java-spring-boot-for-the-ledger.md) | Java 21 and Spring Boot for the ledger | Accepted |
| [003](ADR-003-python-fastapi-for-assistant-and-analysis.md) | Python 3.13 and FastAPI for the assistant | Accepted |
| [004](ADR-004-http-openapi-service-contract.md) | HTTP with a versioned OpenAPI document as the contract | Accepted |
| [005](ADR-005-docker-compose-as-the-run-mechanism.md) | Compose is the mechanism, the Makefile the interface | Accepted |
| [006](ADR-006-postgresql-for-the-ledger-store.md) | PostgreSQL as the ledger's store | Accepted, resolved by 024 |
| [007](ADR-007-asymmetric-hexagonal-architecture.md) | Asymmetric hexagonal: tactical DDD in the ledger only | Accepted, extended by 010 |
| [008](ADR-008-backend-frontend-root-layout.md) | `backend/` and `frontend/` as the root split | Accepted |
| [009](ADR-009-react-vite-spa-for-the-web-client.md) | React + Vite SPA for the web client | Accepted, extended by 029 |
| [010](ADR-010-bounded-context-map.md) | The context map, and context-first packaging | Accepted, extended by 015 |
| [011](ADR-011-double-entry-bookkeeping.md) | Double-entry, five account types, transaction lifecycle | Accepted |
| [012](ADR-012-multi-currency-without-conversion.md) | Multi-currency amounts, with no conversion | Accepted |
| [013](ADR-013-fifo-lot-based-cost-basis.md) | Lot-based holdings with FIFO cost basis | Accepted, extended by 041 |
| [014](ADR-014-grounded-answers-require-citations.md) | An answer stating a figure must cite the ledger | Accepted |
| [015](ADR-015-planning-as-a-sixth-bounded-context.md) | `planning` as a sixth bounded context, in the ledger | Accepted |
| [016](ADR-016-deterministic-per-currency-projection.md) | Deterministic, per-currency projections | Accepted |
| [017](ADR-017-test-strategy.md) | Coverage measured and never gated; strength by mutation | Accepted |
| [018](ADR-018-cross-language-money-vectors.md) | The two Money kernels pinned to a shared vector file | Accepted |
| [019](ADR-019-opentelemetry-and-structured-logging.md) | OpenTelemetry, structured logs, one redaction boundary | Accepted |
| [020](ADR-020-audit-trail-follows-persistence.md) | The journal is the audit record; it lands with persistence | Accepted, extended by 033 |
| [021](ADR-021-application-layer-shape.md) | The application layer: use-case ports, consumer-owned out-ports | Accepted, extended by 041 |
| [022](ADR-022-ledger-composition-root.md) | A composition root package in the ledger | Accepted |
| [023](ADR-023-domain-events-in-the-ledger.md) | Domain events in the ledger, raised by the application service | Accepted |
| [024](ADR-024-jdbc-persistence-and-append-only-schema.md) | Hand-written SQL over `JdbcClient`, and an append-only journal schema | Accepted, extended by 033 and 035 |
| [025](ADR-025-integration-tests-on-testcontainers.md) | Integration tests against a real PostgreSQL, in Testcontainers | Accepted |
| [026](ADR-026-web-client-calls-both-services-through-one-origin.md) | The web client calls both services directly, through one origin | Accepted, extended by 030 |
| [027](ADR-027-defer-authentication-and-bind-to-loopback.md) | Authentication deferred, and every service bound to loopback | Superseded by 030 |
| [028](ADR-028-http-wire-conventions.md) | HTTP wire conventions, for both services | Accepted, extended by 030 |
| [029](ADR-029-web-client-stack.md) | The web client's routing, server state, contract types and styling | Accepted, extended by 030 |
| [030](ADR-030-oidc-authentication-through-a-bff-gateway.md) | OIDC authentication through a backend-for-frontend gateway | Accepted, extended by 037 |
| [031](ADR-031-keycloak-as-the-identity-provider.md) | Keycloak as the identity provider | Accepted, extended by 036 |
| [032](ADR-032-role-based-authorization-per-service.md) | Role-based authorization, owned by each service | Accepted |
| [033](ADR-033-the-journal-records-who.md) | The journal records who wrote each row, from `V1_2` onward | Accepted, corrected by 035 |
| [034](ADR-034-keep-the-compose-smoke-check-manual.md) | The Compose authentication smoke check stays manual, out of CI | Accepted, extended by 040 |
| [035](ADR-035-flyway-runs-out-of-order.md) | Flyway runs out of order, because every context owns a major version | Accepted |
| [036](ADR-036-least-privilege-keycloak-client-scoping.md) | Deny each Keycloak client the realm's full scope | Accepted |
| [037](ADR-037-an-unusable-key-set-is-an-outage.md) | A key set that cannot be read, or holds no key, is an outage, on both services | Accepted |
| [038](ADR-038-back-up-each-database-as-a-logical-dump.md) | Back up each database as its own logical dump, on demand, proven by an isolated restore check | Accepted |
| [039](ADR-039-compose-restarts-services-and-bounds-their-logs.md) | Compose restarts a crashed service, and keeps at most 30 MB of each container's logs | Accepted |
| [040](ADR-040-the-smoke-check-trigger-fired.md) | ADR-034's first trigger fired: the smoke check stays manual, and CI starts the packaged gateway | Accepted |
| [041](ADR-041-consume-fifo-orders-its-own-lots.md) | `consume_fifo` orders its own lots, oldest first | Accepted |

**Proposed** means written down and not yet ratified — nothing implements it, and it can
still change cheaply. No record is Proposed today.

006 was ratified on 2026-09-06 with the persistence layer, and 024 and 025 are the two
decisions it and 017 had reserved for that moment — the access strategy, and how a schema is
tested.

004 was ratified on 2026-09-14, ahead of the first endpoint rather than with it: the web client
and the ledger's first controllers both need a settled contract to be built against. 026
records how the web client uses the contracts, and 028 the wire conventions both services
follow.

030 superseded 027 on 2026-09-16, although none of 027's revisit triggers had fired: the owner
chose to build authentication to a professional standard before the threat model asked for it.
030 keeps 027's loopback binding, Host allow-lists and JSON-only writes as defence in depth. 031
records the identity provider it relies on, and 032 the authorisation model.

033 extends 020 and 024 on 2026-09-22, once authentication existed to discharge the "who" both
records had deferred: every journal row now names the authenticated subject that wrote it, from
`V1_2__journal_recorded_by.sql` onward. 021's rule that identity is generated inside the service
still holds — an actor travels beside a command, never inside one.

034 records, on 2026-09-22, why `ops/smoke/auth_smoke.py` is run by hand and never by CI, and
weighs the evidence against that choice: the check's first real run found a defect no CI job
would have caught, whose class a cheap unit test now guards.

035 corrects 024 and 033 on 2026-09-22, after review found that `V1_2` would have stopped the
owner's ledger from starting: a major version per context means a new migration for any context
but the highest arrives below a version already applied, which Flyway refuses unless it is told
to run out of order. `LedgerUpgradeIT` now starts the ledger against the history a real database
actually holds.

036 extends 031 on 2026-09-22: a run against the real Keycloak image showed both clients carrying
the realm's full scope by Keycloak's own default, rather than only the roles each one's tokens
need. Nothing was exploitable — every consumer already ignores a role it does not recognise — but
the claim's meaning was wrong. `fullScopeAllowed: false` plus an explicit `scopeMappings` entry
per client is the fix, pinned statically by `KeycloakRealmTest` and against a running Keycloak by
`KeycloakRealmIT`.

037 extends 030 on 2026-10-10: a JWKS endpoint that answers, but with nothing a token can be
verified against, is the same outage as one that cannot be reached, on both services. Before it,
the ledger and the assistant disagreed on which such bodies were outages and which were refused
tokens, and an empty key set was a 401 on both, so nobody was paged while no request could be
authenticated ([#126](https://github.com/NaimElijah/FinAssist/issues/126)).

038 records on 2026-10-13 how the books and the identity store are backed up, now that there are
real ones to lose. It builds on 031, which made the two databases separate failure domains, and on
024 and 033, whose triggers and `NOT VALID` checks a restore must never fight. Each database is
dumped on its own, on demand, because the owner has set no recovery-point target. A restore writes
only into an empty database. A restore check in an isolated Compose project is what proves a
backup restores ([#137](https://github.com/NaimElijah/FinAssist/issues/137)).

039 decides on 2026-10-13 what ADR-019 left to "whatever runs the container", for the one runtime
FinAssist has, Compose. A crashed service, and the whole stack after Docker restarts, comes back
under `unless-stopped`. Each container keeps at most three 10 MB `json-file` logs. Every
healthcheck probes readiness. CI's `compose` job checks all three against the resolved file
([#142](https://github.com/NaimElijah/FinAssist/issues/142)). 038 was written first, for #137,
on a branch that merges after this one.

040 extends 034 on 2026-10-13, because 034's first revisit trigger fired: a defect only a running
stack would have caught, a test-scoped `snakeyaml` that kept the packaged gateway from starting at
all, reached `dev` and `main` ([#233](https://github.com/NaimElijah/FinAssist/issues/233)). The
smoke check stays manual. Following 034's own rule, the class that slipped through gets a cheap CI
guard: `GatewayPackagedJarIT` starts the packaged gateway on every push, and
[#234](https://github.com/NaimElijah/FinAssist/issues/234) brings the same check to the ledger and
the assistant.

041 extends 013 and 021 on 2026-10-15. 013 left lot order to the caller, and named a trigger:
enforce it inside `consume_fifo` the first time a caller gets it wrong. No caller had yet, but
[#158](https://github.com/NaimElijah/FinAssist/issues/158) is about to add a second one, and a
wrong order raises nothing. It yields a LIFO gain that looks right. So the function now sorts its
own lots, oldest first, keeping same-day lots in the order given. `DisposeUnits`, where 021 had put
the sort, no longer sorts at all, as 021's own trigger for a use case holding a rule asks
([#69](https://github.com/NaimElijah/FinAssist/issues/69)).
