# ADR-006: PostgreSQL as the ledger's store

- **Status:** Proposed
- **Date:** 2026-08-29

## Context

The ledger owns accounts, transactions and balances. That data is relational, benefits from
real constraints, and needs exact decimal arithmetic — a floating-point representation of
money is a defect, not a trade-off.

No persistence code exists yet. This ADR is **Proposed**: `docker-compose.yml` runs a
Postgres container so the decision can be tried, but nothing connects to it, and
`ledger/pom.xml` deliberately carries no driver or ORM dependency until this is accepted.

## Decision

PostgreSQL 18 is the ledger's store, and the ledger is its sole writer. Money is stored as
`NUMERIC`, never a floating-point type. The assistant reads through the ledger's API
([ADR-004](ADR-004-http-openapi-service-contract.md)), never through this database.

## Consequences

**Makes easy**

- Uniqueness, foreign keys and check constraints enforce invariants at the storage layer as
  well as in the domain, so a bug in one is caught by the other.
- `NUMERIC` gives exact decimal arithmetic that maps cleanly onto Java's `BigDecimal`.
- Ubiquitous tooling, and one container in Compose to run it.

**Makes hard**

- A schema and a migration discipline are needed before the first write, and migrations are
  append-only once released.
- One more service to run, back up and keep current. Its image tag is pinned by hand;
  Dependabot does not cover Compose files.

## Open

The access strategy is deliberately not bundled into this ADR: JPA with Hibernate, Spring
Data JDBC, and jOOQ are all live options, and the choice interacts with how much of the
domain the persistence layer is allowed to see. It needs its own ADR before the first
entity is written.

## Alternatives considered

- **SQLite** — sufficient for a single-user tracker and far simpler to operate, but it
  gives up concurrent writers and the option of running the services on separate hosts,
  both of which the split in [ADR-001](ADR-001-polyglot-java-python-split.md) assumes.
- **A document store (MongoDB and similar)** — the data is relational and constraint-heavy;
  a schemaless store would move invariant enforcement entirely into application code, which
  is the opposite of this project's stated direction.
- **An event-sourced ledger** — a genuinely good fit for double-entry accounting and worth
  revisiting, but a large commitment to make before a single requirement is implemented.
