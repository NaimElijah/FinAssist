# Architecture Decision Records

One file per decision, `ADR-NNN-short-kebab-title.md`, numbered monotonically. These are
append-only history: to change course, add a new record and mark the old one
`Superseded by ADR-NNN`. Never rewrite an accepted one.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-polyglot-java-python-split.md) | Split into a Java ledger and a Python assistant | Accepted |
| [002](ADR-002-java-spring-boot-for-the-ledger.md) | Java 21 and Spring Boot for the ledger | Accepted |
| [003](ADR-003-python-fastapi-for-assistant-and-analysis.md) | Python 3.13 and FastAPI for the assistant | Accepted |
| [004](ADR-004-http-openapi-service-contract.md) | HTTP with a versioned OpenAPI document as the contract | Proposed |
| [005](ADR-005-docker-compose-as-the-run-mechanism.md) | Compose is the mechanism, the Makefile the interface | Accepted |
| [006](ADR-006-postgresql-for-the-ledger-store.md) | PostgreSQL as the ledger's store | Proposed |
| [007](ADR-007-asymmetric-hexagonal-architecture.md) | Asymmetric hexagonal: tactical DDD in the ledger only | Accepted, extended by 010 |
| [008](ADR-008-backend-frontend-root-layout.md) | `backend/` and `frontend/` as the root split | Accepted |
| [009](ADR-009-react-vite-spa-for-the-web-client.md) | React + Vite SPA for the web client | Accepted |
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

**Proposed** means written down and not yet ratified — nothing implements it, and it can
still change cheaply. 004 and 006 remain Proposed: the domain layer needs neither a wire
contract nor a database, so ratifying them was deliberately deferred rather than guessed.
Resolve both before the first line of persistence or client code.
