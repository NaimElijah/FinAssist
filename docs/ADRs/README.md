# Architecture Decision Records

One file per decision, `ADR-NNN-short-kebab-title.md`, numbered monotonically. These are
append-only history: to change course, add a new record and mark the old one
`Superseded by ADR-NNN`. Never rewrite an accepted one.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-polyglot-java-python-split.md) | Split into a Java ledger and a Python assistant | Accepted, corrected by 047 |
| [002](ADR-002-java-spring-boot-for-the-ledger.md) | Java 21 and Spring Boot for the ledger | Accepted, corrected by 047 |
| [003](ADR-003-python-fastapi-for-assistant-and-analysis.md) | Python 3.13 and FastAPI for the assistant | Accepted, extended by 046 |
| [004](ADR-004-http-openapi-service-contract.md) | HTTP with a versioned OpenAPI document as the contract | Accepted |
| [005](ADR-005-docker-compose-as-the-run-mechanism.md) | Compose is the mechanism, the Makefile the interface | Accepted, extended by 049, corrected by 047 |
| [006](ADR-006-postgresql-for-the-ledger-store.md) | PostgreSQL as the ledger's store | Accepted, resolved by 024 |
| [007](ADR-007-asymmetric-hexagonal-architecture.md) | Asymmetric hexagonal: tactical DDD in the ledger only | Accepted, extended by 010 |
| [008](ADR-008-backend-frontend-root-layout.md) | `backend/` and `frontend/` as the root split | Accepted, extended by 052 |
| [009](ADR-009-react-vite-spa-for-the-web-client.md) | React + Vite SPA for the web client | Accepted, extended by 029 |
| [010](ADR-010-bounded-context-map.md) | The context map, and context-first packaging | Accepted, extended by 015 and 046, corrected by 047 |
| [011](ADR-011-double-entry-bookkeeping.md) | Double-entry, five account types, transaction lifecycle | Accepted |
| [012](ADR-012-multi-currency-without-conversion.md) | Multi-currency amounts, with no conversion | Accepted |
| [013](ADR-013-fifo-lot-based-cost-basis.md) | Lot-based holdings with FIFO cost basis | Accepted, extended by 041 |
| [014](ADR-014-grounded-answers-require-citations.md) | An answer stating a figure must cite the ledger | Accepted |
| [015](ADR-015-planning-as-a-sixth-bounded-context.md) | `planning` as a sixth bounded context, in the ledger | Accepted |
| [016](ADR-016-deterministic-per-currency-projection.md) | Deterministic, per-currency projections | Accepted |
| [017](ADR-017-test-strategy.md) | Coverage measured and never gated; strength by mutation | Accepted |
| [018](ADR-018-cross-language-money-vectors.md) | The two Money kernels pinned to a shared vector file | Accepted |
| [019](ADR-019-opentelemetry-and-structured-logging.md) | OpenTelemetry, structured logs, one redaction boundary | Accepted, extended by 050, corrected by 047 |
| [020](ADR-020-audit-trail-follows-persistence.md) | The journal is the audit record; it lands with persistence | Accepted, extended by 033 |
| [021](ADR-021-application-layer-shape.md) | The application layer: use-case ports, consumer-owned out-ports | Accepted, extended by 041 |
| [022](ADR-022-ledger-composition-root.md) | A composition root package in the ledger | Accepted, extended by 051 |
| [023](ADR-023-domain-events-in-the-ledger.md) | Domain events in the ledger, raised by the application service | Accepted, extended by 048, which supersedes its in-memory-log sentence |
| [024](ADR-024-jdbc-persistence-and-append-only-schema.md) | Hand-written SQL over `JdbcClient`, and an append-only journal schema | Accepted, extended by 033 and 035 |
| [025](ADR-025-integration-tests-on-testcontainers.md) | Integration tests against a real PostgreSQL, in Testcontainers | Accepted, extended by 047 |
| [026](ADR-026-web-client-calls-both-services-through-one-origin.md) | The web client calls both services directly, through one origin | Accepted, extended by 030 |
| [027](ADR-027-defer-authentication-and-bind-to-loopback.md) | Authentication deferred, and every service bound to loopback | Superseded by 030 |
| [028](ADR-028-http-wire-conventions.md) | HTTP wire conventions, for both services | Accepted, extended by 030 |
| [029](ADR-029-web-client-stack.md) | The web client's routing, server state, contract types and styling | Accepted, extended by 030 and 047 |
| [030](ADR-030-oidc-authentication-through-a-bff-gateway.md) | OIDC authentication through a backend-for-frontend gateway | Accepted, extended by 037 and 053, corrected by 047 |
| [031](ADR-031-keycloak-as-the-identity-provider.md) | Keycloak as the identity provider | Accepted, extended by 036, corrected by 047 |
| [032](ADR-032-role-based-authorization-per-service.md) | Role-based authorization, owned by each service | Accepted, corrected by 047 |
| [033](ADR-033-the-journal-records-who.md) | The journal records who wrote each row, from `V1_2` onward | Accepted, corrected by 035 |
| [034](ADR-034-keep-the-compose-smoke-check-manual.md) | The Compose authentication smoke check stays manual, out of CI | Accepted, extended by 040 |
| [035](ADR-035-flyway-runs-out-of-order.md) | Flyway runs out of order, because every context owns a major version | Accepted, corrected by 047 |
| [036](ADR-036-least-privilege-keycloak-client-scoping.md) | Deny each Keycloak client the realm's full scope | Accepted |
| [037](ADR-037-an-unusable-key-set-is-an-outage.md) | A key set that cannot be read, or holds no key, is an outage, on both services | Accepted |
| [038](ADR-038-back-up-each-database-as-a-logical-dump.md) | Back up each database as its own logical dump, on demand, proven by an isolated restore check | Accepted |
| [039](ADR-039-compose-restarts-services-and-bounds-their-logs.md) | Compose restarts a crashed service, and keeps at most 30 MB of each container's logs | Accepted, extended by 044 |
| [040](ADR-040-the-smoke-check-trigger-fired.md) | ADR-034's first trigger fired: the smoke check stays manual, and CI starts the packaged gateway | Accepted |
| [041](ADR-041-consume-fifo-orders-its-own-lots.md) | `consume_fifo` orders its own lots, oldest first | Accepted |
| [042](ADR-042-agent-instructions-per-codebase.md) | Agent instructions split per codebase, one `CLAUDE.md` beside each | Accepted |
| [043](ADR-043-github-secret-scanning-and-dependabot-alerts.md) | GitHub's own secret scanning, push protection and Dependabot alerts; no scanner in CI | Accepted, extended by 044 |
| [044](ADR-044-compose-refuses-a-published-secret.md) | Compose refuses to start on a secret that a file in the repository holds | Accepted |
| [045](ADR-045-dependabot-triage-and-toolchain-holds.md) | Dependabot's updates are triaged weekly, the Java pair moves together, and the toolchain lines move only by ADR | Accepted, extended by 047 |
| [046](ADR-046-architecture-rules-read-the-code-never-load-it.md) | Architecture rules read the code and never load it | Accepted |
| [047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md) | Twelve passages of Accepted records reconciled with the system as built | Accepted |
| [048](ADR-048-domain-events-stay-in-process-until-a-consumer-exists.md) | Domain events stay in-process until a consumer exists, and production keeps no event log | Accepted |
| [049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md) | A check that needs CI's throwaway environment stays in CI; every other check is a Make target | Accepted |
| [050](ADR-050-no-domain-metric-yet-decided-again.md) | No domain metric yet, decided again, with new triggers | Accepted |
| [051](ADR-051-what-a-translation-may-decide.md) | What a translation may decide: choosing records stays in `composition`, a rule in the downstream's own words moves into it | Accepted |
| [052](ADR-052-ops-holds-operator-code-one-directory-per-tool.md) | `ops/` holds operator code, one directory per tool | Accepted |
| [053](ADR-053-the-gateways-outbound-calls-speak-http-1-1-under-one-time-budget.md) | The gateway's outbound calls speak HTTP/1.1, under one time budget | Accepted |

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

031's last revisit trigger was discharged on 2026-09-21, five days after the record. Before
`keycloak-db-data` is ever recreated while the journal holds attributed rows, it asks that the
owner's user id be pinned in the realm file. `ops/keycloak/finassist-realm.json` has pinned it
since then, and `KeycloakRealmIT` proves that Keycloak keeps the pinned id on import. So the
accepted cost 031 names for recreating that volume is smaller than it says: the owner repeats the
first sign-in, TOTP included, but keeps the same `sub`, and every journal row they wrote still
names them. 031's own text stays as written, because these records are append-only.

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

042 records on 2026-10-17 how the instructions for Claude Code are laid out. Before it, one
602-line `.claude/CLAUDE.md` loaded into every session. Now a root file under 200 lines holds what
applies everywhere, and each codebase has a `CLAUDE.md` of its own. That file loads only once a
session touches a file in that codebase
([#287](https://github.com/NaimElijah/FinAssist/issues/287)).

043 records on 2026-10-17 what watches the repository now that it is public. Dependabot alerts,
secret scanning and push protection were turned on. A full-history gitleaks scan found nothing
but false positives, so no scanner was added to CI. Security updates stay off, because they
would open their pull requests against `main` and skip `dev`
([#139](https://github.com/NaimElijah/FinAssist/issues/139)).

044 extends 039 and 043 on 2026-10-18. The repository publishes throwaway passwords on purpose,
in CI and in the integration tests, and a real `.env` could reuse one unnoticed: 043's scanning
matches providers' token formats, never a generic password. PostgreSQL applies a password only
to an empty volume, so the check has to run before either database first starts. A one-shot
Compose service, `secret-check`, does that: both databases and Grafana wait on it. It is the
second one-shot that 039's restart and log rules exempt
([#302](https://github.com/NaimElijah/FinAssist/issues/302)).

045 records on 2026-10-20 how Dependabot's version updates are handled, after 26 of them had
piled up unmerged, their checks stale and the two Java services drifting apart. Temurin and
Python stay on the lines ADR-002 and ADR-003 chose until an ADR moves them, the ledger and the
gateway take each bump in one PR, a Keycloak, PyJWT or Spring Security bump goes to the owner
first, and the PRs are triaged in the week they open
([#138](https://github.com/NaimElijah/FinAssist/issues/138)).

046 extends 003 and 010 on 2026-10-29, because both describe a mechanism that was removed the day
they were written. The assistant's architecture tests have parsed source with `ast` since
`ed813b1`; they never import the code. Every architecture rule in the ledger, the assistant and
the web client now reads it, and is shown to fail on a planted violation in a deep module before it
is trusted ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

047 corrects nine records and extends three on 2026-10-29, in twelve passages where the system as
built contradicts or exceeds what an Accepted record says, found by the architecture scan
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). `/error` is no public path, the realm
declares no client scope, the gateway answers no `NOT_FOUND`, and order within a context is held by
`ReleasedMigrationsTest` after all. Two items decide: the raw commands are the supported path on
Windows, and a Maven major moves by ADR while its minor and patch releases move by Dependabot's
weekly triage.

048 extends 023 on 2026-10-29, because 023's first revisit trigger fired on the first repository
(2026-09-07) and nothing replaced the in-memory event log, which grew with every write the running
ledger recorded. The services still raise every event and their tests still pin each one;
production now discards them, the recording logs are test doubles, and the outbox waits for its
first consumer ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

049 extends 005 on 2026-10-29, because 005 promised that CI invokes the same targets a developer
does, and two CI jobs held checks that no target exposed. The published-secret refusal starts
containers on published values under the owner's Compose project name, so it stays inline in
`ci.yml`, with the `.env` seeding it needs. Every other check is a Make target, so the
contract-types check and the Compose conventions check became `make check-web-api` and
`make check-compose` ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

050 extends 019 on 2026-10-29, because 019's trigger for a domain metric fired on 2026-09-14, when
the first business endpoints shipped, and the position was inherited rather than revisited. It is
kept: nothing pages anyone, and the HTTP server metrics already carry the ledger's error rate. The
new triggers are the first alerting rule, the first deployment beyond the owner's machine, and a
failure the owner learns of from the books ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

051 extends 022 on 2026-10-29, answering its trigger for a decision held in `composition`. Choosing
which upstream records answer a question stays translation, so "an `EXPENSE` line is spend" stays.
A rule stated wholly in the downstream's own vocabulary is that context's invariant, so planning
now refuses an account named twice itself. The answer is the same 422, but it now comes before the
scenario is looked up ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

052 extends 008 on 2026-10-29, answering its trigger, which fired three times: the smoke check, the
backup CLI and the secret check are neither backend nor frontend, and none is a service. The tier
split stays, and operator code lives under `ops/<tool>/`, one directory per tool, borrowing the
assistant's toolchain ([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

053 extends 030 on 2026-10-29, recording how the gateway's own outbound calls behave, which four
fixes between 2026-09-22 and 2026-10-09 settled and no record held. They speak HTTP/1.1, because
the JDK client's h2c upgrade offer made uvicorn answer 400 to every relayed POST. They share one 5 s
and 10 s budget, and the relay removes four browser headers, not two
([#339](https://github.com/NaimElijah/FinAssist/issues/339)).
