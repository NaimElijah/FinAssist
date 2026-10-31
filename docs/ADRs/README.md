# Architecture Decision Records

One file per decision, `ADR-NNN-short-kebab-title.md`, numbered monotonically. These are
append-only history: to change course, add a new record and mark the old one
`Superseded by ADR-NNN`. Never rewrite an accepted one.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-polyglot-java-python-split.md) | Split into a Java ledger and a Python assistant | Accepted, extended by 030, corrected by 047 |
| [002](ADR-002-java-spring-boot-for-the-ledger.md) | Java 21 and Spring Boot for the ledger | Accepted, its Java 21 line held by 045, extended by 062, corrected by 047 |
| [003](ADR-003-python-fastapi-for-assistant-and-analysis.md) | Python 3.13 and FastAPI for the assistant | Accepted, extended by 046 and 062, its Python 3.13 line held by 045 |
| [004](ADR-004-http-openapi-service-contract.md) | HTTP with a versioned OpenAPI document as the contract | Accepted |
| [005](ADR-005-docker-compose-as-the-run-mechanism.md) | Compose is the mechanism, the Makefile the interface | Accepted, extended by 049 and 054, corrected by 047 |
| [006](ADR-006-postgresql-for-the-ledger-store.md) | PostgreSQL as the ledger's store | Accepted, resolved by 024 |
| [007](ADR-007-asymmetric-hexagonal-architecture.md) | Asymmetric hexagonal: tactical DDD in the ledger only | Accepted, extended by 010 |
| [008](ADR-008-backend-frontend-root-layout.md) | `backend/` and `frontend/` as the root split | Accepted, extended by 052 |
| [009](ADR-009-react-vite-spa-for-the-web-client.md) | React + Vite SPA for the web client | Accepted, extended by 029 |
| [010](ADR-010-bounded-context-map.md) | The context map, and context-first packaging | Accepted, extended by 015, 029 and 046, corrected by 047 |
| [011](ADR-011-double-entry-bookkeeping.md) | Double-entry, five account types, transaction lifecycle | Accepted |
| [012](ADR-012-multi-currency-without-conversion.md) | Multi-currency amounts, with no conversion | Accepted |
| [013](ADR-013-fifo-lot-based-cost-basis.md) | Lot-based holdings with FIFO cost basis | Accepted, extended by 041 |
| [014](ADR-014-grounded-answers-require-citations.md) | An answer stating a figure must cite the ledger | Accepted |
| [015](ADR-015-planning-as-a-sixth-bounded-context.md) | `planning` as a sixth bounded context, in the ledger | Accepted |
| [016](ADR-016-deterministic-per-currency-projection.md) | Deterministic, per-currency projections | Accepted |
| [017](ADR-017-test-strategy.md) | Coverage measured and never gated; strength by mutation | Accepted, extended by 062 and 065, its Testcontainers deferral resolved by 025 |
| [018](ADR-018-cross-language-money-vectors.md) | The two Money kernels pinned to a shared vector file | Accepted |
| [019](ADR-019-opentelemetry-and-structured-logging.md) | OpenTelemetry, structured logs, one redaction boundary | Accepted, extended by 030, 039, 050 and 060, corrected by 047 |
| [020](ADR-020-audit-trail-follows-persistence.md) | The journal is the audit record; it lands with persistence | Accepted, acted on by 023, extended by 033 and 066, its persistence obligation resolved by 024 |
| [021](ADR-021-application-layer-shape.md) | The application layer: use-case ports, consumer-owned out-ports | Accepted, extended by 041 and 055 |
| [022](ADR-022-ledger-composition-root.md) | A composition root package in the ledger | Accepted, extended by 051, with a bounded exception in 028 |
| [023](ADR-023-domain-events-in-the-ledger.md) | Domain events in the ledger, raised by the application service | Accepted, extended by 048, which supersedes its in-memory-log sentence |
| [024](ADR-024-jdbc-persistence-and-append-only-schema.md) | Hand-written SQL over `JdbcClient`, and an append-only journal schema | Accepted, extended by 033, 035 and 066 |
| [025](ADR-025-integration-tests-on-testcontainers.md) | Integration tests against a real PostgreSQL, in Testcontainers | Accepted, extended by 047 |
| [026](ADR-026-web-client-calls-both-services-through-one-origin.md) | The web client calls both services directly, through one origin | Accepted, extended by 030 |
| [027](ADR-027-defer-authentication-and-bind-to-loopback.md) | Authentication deferred, and every service bound to loopback | Superseded by 030 |
| [028](ADR-028-http-wire-conventions.md) | HTTP wire conventions, for both services | Accepted, extended by 030, 056, 058 and 068 |
| [029](ADR-029-web-client-stack.md) | The web client's routing, server state, contract types and styling | Accepted, extended by 030, 047, 056, 057 and 062 |
| [030](ADR-030-oidc-authentication-through-a-bff-gateway.md) | OIDC authentication through a backend-for-frontend gateway | Accepted, extended by 037, 053, 057, 058 and 067, corrected by 047 |
| [031](ADR-031-keycloak-as-the-identity-provider.md) | Keycloak as the identity provider | Accepted, extended by 036 and 060, corrected by 047 and 064 |
| [032](ADR-032-role-based-authorization-per-service.md) | Role-based authorization, owned by each service | Accepted, corrected by 047 |
| [033](ADR-033-the-journal-records-who.md) | The journal records who wrote each row, from `V1_2` onward | Accepted, corrected by 035 |
| [034](ADR-034-keep-the-compose-smoke-check-manual.md) | The Compose authentication smoke check stays manual, out of CI | Accepted, extended by 040 |
| [035](ADR-035-flyway-runs-out-of-order.md) | Flyway runs out of order, because every context owns a major version | Accepted, corrected by 047 |
| [036](ADR-036-least-privilege-keycloak-client-scoping.md) | Deny each Keycloak client the realm's full scope | Accepted |
| [037](ADR-037-an-unusable-key-set-is-an-outage.md) | A key set that cannot be read, or holds no key, is an outage, on both services | Accepted, extended by 059 |
| [038](ADR-038-back-up-each-database-as-a-logical-dump.md) | Back up each database as its own logical dump, on demand, proven by an isolated restore check | Accepted |
| [039](ADR-039-compose-restarts-services-and-bounds-their-logs.md) | Compose restarts a crashed service, and keeps at most 30 MB of each container's logs | Accepted, extended by 044 |
| [040](ADR-040-the-smoke-check-trigger-fired.md) | ADR-034's first trigger fired: the smoke check stays manual, and CI starts the packaged gateway | Accepted |
| [041](ADR-041-consume-fifo-orders-its-own-lots.md) | `consume_fifo` orders its own lots, oldest first | Accepted |
| [042](ADR-042-agent-instructions-per-codebase.md) | Agent instructions split per codebase, one `CLAUDE.md` beside each | Accepted |
| [043](ADR-043-github-secret-scanning-and-dependabot-alerts.md) | GitHub's own secret scanning, push protection and Dependabot alerts; no scanner in CI | Accepted, extended by 044 |
| [044](ADR-044-compose-refuses-a-published-secret.md) | Compose refuses to start on a secret that a file in the repository holds | Accepted, extended by 061 |
| [045](ADR-045-dependabot-triage-and-toolchain-holds.md) | Dependabot's updates are triaged weekly, the Java pair moves together, and the toolchain lines move only by ADR | Accepted, extended by 047, 062 and 064 |
| [046](ADR-046-architecture-rules-read-the-code-never-load-it.md) | Architecture rules read the code and never load it | Accepted |
| [047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md) | Twelve passages of Accepted records reconciled with the system as built | Accepted |
| [048](ADR-048-domain-events-stay-in-process-until-a-consumer-exists.md) | Domain events stay in-process until a consumer exists, and production keeps no event log | Accepted |
| [049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md) | A check that needs CI's throwaway environment stays in CI; every other check is a Make target | Accepted, extended by 061 and 063 |
| [050](ADR-050-no-domain-metric-yet-decided-again.md) | No domain metric yet, decided again, with new triggers | Accepted |
| [051](ADR-051-what-a-translation-may-decide.md) | What a translation may decide: choosing records stays in `composition`, a rule in the downstream's own words moves into it | Accepted |
| [052](ADR-052-ops-holds-operator-code-one-directory-per-tool.md) | `ops/` holds operator code, one directory per tool | Accepted |
| [053](ADR-053-the-gateways-outbound-calls-speak-http-1-1-under-one-time-budget.md) | The gateway's outbound calls speak HTTP/1.1, under one time budget | Accepted, extended by 067, which supersedes its 502-on-timeout line |
| [054](ADR-054-each-service-ships-as-a-two-stage-image-run-as-a-non-root-user.md) | Each service ships as a two-stage image, built from source and run as a non-root user | Accepted, extended by 063 and 064 |
| [055](ADR-055-the-assistants-development-seed-lives-in-its-composition-root.md) | The assistant's development seed lives in its composition root, and is off by default | Accepted |
| [056](ADR-056-the-web-client-retries-a-failed-read-once-and-never-a-write.md) | The web client retries a failed read once, and never a write | Accepted |
| [057](ADR-057-a-page-on-another-localhost-port-is-treated-as-hostile.md) | A page on another `localhost` port is treated as hostile | Accepted |
| [058](ADR-058-each-service-bounds-what-one-request-can-cost.md) | Each service bounds what one request can cost | Accepted |
| [059](ADR-059-the-assistant-trusts-only-the-key-set-it-last-fetched.md) | The assistant trusts only the key set it last fetched | Accepted |
| [060](ADR-060-the-finassist-realm-and-the-keycloak-image-are-configured-not-defaulted.md) | The `finassist` realm and the Keycloak image are configured, not defaulted | Accepted |
| [061](ADR-061-the-stack-refuses-a-configuration-that-weakens-a-credential.md) | The stack refuses a configuration that weakens a credential | Accepted |
| [062](ADR-062-every-kept-dependency-is-justified-and-has-a-revisit-trigger.md) | Every kept third-party dependency is justified, and each has a revisit trigger | Accepted, corrected by 064 |
| [063](ADR-063-every-container-holds-only-the-privilege-it-uses.md) | Under Compose, every container holds only the privilege it uses, and `make check-compose` holds it there | Accepted |
| [064](ADR-064-what-the-build-fetches-is-pinned-by-content.md) | What the build fetches is pinned by content where the tool allows it, and Dependabot moves every pin | Accepted |
| [065](ADR-065-javac-lint-is-the-java-lint-gate.md) | javac's own lint, every category, is the Java services' lint gate | Accepted |
| [066](ADR-066-the-store-holds-every-rule-the-domain-holds-across-rows.md) | The ledger's store holds every rule the domain holds across rows | Accepted |
| [067](ADR-067-a-relay-that-times-out-answers-504-outcome-unknown.md) | A relay that times out answers 504, outcome unknown | Accepted |
| [068](ADR-068-every-problem-carries-what-its-contract-requires.md) | Every problem carries what its contract requires | Accepted |

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
still holds — a command may carry the actor who issued it, but never an identity for what it
creates.

034 records, on 2026-09-22, why `ops/smoke/auth_smoke.py` is run by hand and never by CI, and
weighs the evidence against that choice: the check's first real run found a defect no CI job
would have caught, whose class a cheap unit test now guards.

035 extends 024 and corrects 033 on 2026-09-22, after review found that `V1_2` would have stopped the
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
under `unless-stopped`. Each container keeps at most three 10 MB `json-file` logs. Every healthcheck
probes readiness. `make check-compose`, which CI's `compose` job runs, checks all three against the
resolved file ([#142](https://github.com/NaimElijah/FinAssist/issues/142)). 038 was written first,
for #137, on a branch that merges after this one.

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

054 extends 005 on 2026-10-29, recording how the ledger, the gateway and the assistant become
images, which only their Dockerfiles said. Each is built from source in two stages and run as a
non-root user, the build skips the tests, and nothing in CI builds an image
([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

055 extends 021 on 2026-10-29, recording the assistant's development seed, which has existed since
2026-09-14. `ASSISTANT_DEV_SEED` is off by default, the seed lives only in the composition root,
and it is never a data source. It goes when a ledger client and a quote feed exist
([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

056 extends 028 and 029 on 2026-10-29, recording the web client's retry rule, which both imply and
neither states. A failed read is retried once, on a network failure or a 5xx other than 503; a
write is never retried; and a 401 from anything but the session check ends the session
([#339](https://github.com/NaimElijah/FinAssist/issues/339)).

057 extends 030 and 029 on 2026-10-30, because cookies ignore ports, so a page served from any other
`localhost` port is same-site with the app, shares its cookie jar and rides its `Lax` cookies.
ADR-030 used the fact to rename the cookies and no record drew the rest of its consequences. The
gateway now takes the CSRF token from its header alone, starts a sign-in only from a top-level
navigation and refuses any other with 403, lets a started sign-in's session idle 15 minutes, strips
an upstream's cookie, site-data, HSTS and CORS headers, and logs only a registered OAuth2 code. The
dev and preview servers refuse framing and CORS, `/__open-in-editor` answers only the app's own
page, and the lint rule refuses raw-HTML sinks
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

058 extends 028 and 030 on 2026-10-30, because authentication decides who may ask and nothing decided
how much a request may cost, and the services paid that cost before they checked a permission. A
body over 64 KiB is now a 413 `CONTENT_TOO_LARGE` on the ledger, the gateway and the assistant: the
first two before Spring Security, the assistant before authentication when the length is declared.
The ledger bounds a horizon at 100 years, a comparison at 10 scenarios and every date to the years
1 to 9999, and answers an unparseable multipart body and a firewall rejection 400. The assistant
serves through `h11` with a 16 KiB header bound, trusts no forwarded header and upgrades no
WebSocket, and its `Money` accepts what Java's `BigDecimal` does
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

059 extends 037 on 2026-10-30, because 037 made an unusable key set an outage on both services and
left open how long a key already seen stays trusted. The ledger keeps only the fetched set; the
assistant also kept every key it had resolved, for ever, so a key the realm removed kept verifying
until a restart, and an outage went unseen for every key already cached. The assistant now caches no
key apart from the set, so a key leaves with it, and after a failed fetch it answers an outage for
15 seconds without fetching again. 037's line that the assistant's per-`kid` cache is untouched
by a failed refresh no longer holds; 037 keeps its text
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

060 extends 031 and 019 on 2026-10-30, because 031 pinned the settings it chose and left every other
one to Keycloak's default. Four defaults were still in force. The `jboss-logging` listener wrote each
failed sign-in to the log with the username as typed. The unused `admin-cli` client kept its password
grant. Offline sessions had no absolute lifetime. Keycloak logged plain text and enabled two deprecated
features. The realm file and the image now state all of them, and operations.md applies the realm
settings to an existing realm in place, keeping every user. The owner chose on 2026-10-30 not to
require a second factor of a viewer, and 060 records that rejection with its triggers. The `master`
realm and a Host allow-list for Keycloak are
[#368](https://github.com/NaimElijah/FinAssist/issues/368) and
[#369](https://github.com/NaimElijah/FinAssist/issues/369)
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

061 extends 044 and 049 on 2026-10-30. 044 refuses a secret the repository publishes and accepts any
other, a one-character password included; 049 names the keys `make check-compose` refuses and has no
rule for a log level. `secret-check` now also refuses any of its eight secrets shorter than 32
characters, what `openssl rand -hex 16` writes, as the owner chose on 2026-10-30. `make check-compose`
refuses a log level, a log group, a logging configuration or Spring Boot's `DEBUG` or `TRACE` switch
on the gateway, because DEBUG on Spring Web or Security writes the authorization code and its PKCE
verifier. An owner whose `.env` holds a shorter value rotates it before the stack starts
([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

062 extends 045, 029, 003, 002 and 017 on 2026-10-30, because the rule that every third-party
dependency is justified had a record for the dependencies an earlier ADR chose and none for the
rest. Four tables now give every direct dependency of the Java services, the assistant and the web
client that no record covered its reason, its licence and the event that reopens it, and a section
states the licence position against the proprietary `LICENSE`. `anthropic` and `pandas` leave the
assistant because nothing imports them, `uvicorn[standard]` becomes `uvicorn`, and `httpx` moves to
the dev extra. No Maven lockfile is adopted: Boot's parent version is the Java services' lock. Both
Java services override OpenTelemetry to 1.67.0 for GHSA-ccrj-gw8r-854m, until Boot manages it.
Dependabot groups the React packages and holds `@types/node` on Node's major
([#341](https://github.com/NaimElijah/FinAssist/issues/341)).

063 extends 049 and 054 on 2026-10-30. Scan 5 found that `make check-compose` did not check
the loopback publication, `keycloak-db`'s lack of a port or the `smoke` profile, although the
root `CLAUDE.md` listed them among the rules the build enforces. It also found every container
on Docker's default privileges, on one network. Every service but the two databases now drops
every capability and sets `no-new-privileges`, all but those and Keycloak run on a read-only
root, and none takes `privileged`, `cap_add`, a shared namespace, a device, root or the
Docker socket. Each database shares a network with its one client alone, `keycloak-db`'s
internal. The Java images and Keycloak's leave their code owned by root, and Prometheus
grants no CORS origin. The check refuses a break of any of these, naming its exemptions so
that a new service is held too. Still open: the two PostgreSQL containers' capabilities
([#386](https://github.com/NaimElijah/FinAssist/issues/386)), limits
([#387](https://github.com/NaimElijah/FinAssist/issues/387)), whether `db` publishes its
port, which any container on Docker Desktop reaches through `host.docker.internal`
([#388](https://github.com/NaimElijah/FinAssist/issues/388)), and the routes to the host the
check does not yet name ([#389](https://github.com/NaimElijah/FinAssist/issues/389),
[#342](https://github.com/NaimElijah/FinAssist/issues/342)).

064 extends 045 and 054, and corrects 062 and 031, on 2026-10-30. Every GitHub Action is pinned
to a commit SHA with its version in a comment, and the Maven distribution to its SHA-256.
Dependabot keeps both current. The Java build stages install `unzip`, without which `mvnw`
checks the wrong archive. Dependabot's `docker-compose` ecosystem, generally available since
2025-02-25, now proposes Compose's own image tags, holding Python below 3.14 and every
PostgreSQL major, which corrects 062 and 031, whose bodies say the one-shots' Python tag and
`keycloak-db`'s are bumped by hand. The Testcontainers tags still follow a postgres bump by
hand
([#80](https://github.com/NaimElijah/FinAssist/issues/80),
[#342](https://github.com/NaimElijah/FinAssist/issues/342)).

065 extends 017 on 2026-10-30. The Java services had a format check and no lint. Both now
compile with `-Xlint:all -Werror`, main and test code alike, with no category excluded. The 35
warnings it found are fixed rather than suppressed, except two `try` warnings on test methods
that open a scope for its effect alone. A dependency bump that deprecates a call the code makes
now fails the build until the call moves
([#342](https://github.com/NaimElijah/FinAssist/issues/342)).

066 extends 024 and 020 on 2026-10-31. Scan 6 found the store holding each rule a single row
expresses, and few that span rows: a recorded transaction could gain entries, the state history
took any state at any sequence, a header could stand without entries, an account's currency
could change under its entries, and a budget limit or a strategy's flow could sit in another
currency than its parent's. `V1_3` to `V1_7`, `V2_2` and `V3_2` now refuse each, by a composite
foreign key where one can say it without scanning the journal and a trigger otherwise, and the
balance refusal no longer prints the sum. The journal's new key stays `NOT VALID`, as ADR-033's
checks do ([#343](https://github.com/NaimElijah/FinAssist/issues/343)).

067 extends 053 and 030 on 2026-10-31. Scan 7 found the gateway answering a relayed request that
timed out with the 502 `UPSTREAM_UNAVAILABLE` of a connection never opened, telling the owner a
change "could not be reached" when the ledger may have committed it, and inviting a resend that the
append-only journal keeps. A read timeout is now 504 `UPSTREAM_TIMEOUT`, whose detail says the
outcome is unknown and to check before sending again; a refused or unresolvable connection and a
connect timeout stay 502. The two are told apart by exception type, never by message
([#344](https://github.com/NaimElijah/FinAssist/issues/344)).

068 extends 028 on 2026-10-31. Scan 7 found the three contracts requiring only `code` of a problem
though every writer sends `type`, `title` and `status` and the web client relies on `status`, and
the ledger answering a malformed body or parameter 400 with no `errors[]`, which ADR-028's table
promises. Every problem now carries all four members and every `errors` entry a `field` and a
`message`, and all three contracts require them; every malformed JSON body or parameter 400 names what
it refused, in fixed text that quotes nothing sent; and `instance` says it is the path the answering
service received ([#344](https://github.com/NaimElijah/FinAssist/issues/344)).
