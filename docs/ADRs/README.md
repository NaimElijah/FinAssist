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
| [013](ADR-013-fifo-lot-based-cost-basis.md) | Lot-based holdings with FIFO cost basis | Accepted |
| [014](ADR-014-grounded-answers-require-citations.md) | An answer stating a figure must cite the ledger | Accepted |
| [015](ADR-015-planning-as-a-sixth-bounded-context.md) | `planning` as a sixth bounded context, in the ledger | Accepted |
| [016](ADR-016-deterministic-per-currency-projection.md) | Deterministic, per-currency projections | Accepted |
| [017](ADR-017-test-strategy.md) | Coverage measured and never gated; strength by mutation | Accepted |
| [018](ADR-018-cross-language-money-vectors.md) | The two Money kernels pinned to a shared vector file | Accepted |
| [019](ADR-019-opentelemetry-and-structured-logging.md) | OpenTelemetry, structured logs, one redaction boundary | Accepted |
| [020](ADR-020-audit-trail-follows-persistence.md) | The journal is the audit record; it lands with persistence | Accepted |
| [021](ADR-021-application-layer-shape.md) | The application layer: use-case ports, consumer-owned out-ports | Accepted |
| [022](ADR-022-ledger-composition-root.md) | A composition root package in the ledger | Accepted |
| [023](ADR-023-domain-events-in-the-ledger.md) | Domain events in the ledger, raised by the application service | Accepted |
| [024](ADR-024-jdbc-persistence-and-append-only-schema.md) | Hand-written SQL over `JdbcClient`, and an append-only journal schema | Accepted |
| [025](ADR-025-integration-tests-on-testcontainers.md) | Integration tests against a real PostgreSQL, in Testcontainers | Accepted |
| [026](ADR-026-web-client-calls-both-services-through-one-origin.md) | The web client calls both services directly, through one origin | Accepted |
| [027](ADR-027-defer-authentication-and-bind-to-loopback.md) | Authentication deferred, and every service bound to loopback | Accepted |
| [028](ADR-028-http-wire-conventions.md) | HTTP wire conventions, for both services | Accepted |
| [029](ADR-029-web-client-stack.md) | The web client's routing, server state, contract types and styling | Accepted |

**Proposed** means written down and not yet ratified — nothing implements it, and it can
still change cheaply. No record is Proposed today.

006 was ratified on 2026-09-06 with the persistence layer, and 024 and 025 are the two
decisions it and 017 had reserved for that moment — the access strategy, and how a schema is
tested.

004 was ratified on 2026-09-14, ahead of the first endpoint rather than with it: the web client
and the ledger's first controllers both need a settled contract to be built against. 026
records how the web client uses the contracts, and 028 the wire conventions both services
follow.
