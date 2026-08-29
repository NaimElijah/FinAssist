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
| [007](ADR-007-asymmetric-hexagonal-architecture.md) | Asymmetric hexagonal: tactical DDD in the ledger only | Accepted |

**Proposed** means written down and not yet ratified — nothing implements it, and it can
still change cheaply. Resolve 004 and 006 before the first line of persistence or client
code is written.
