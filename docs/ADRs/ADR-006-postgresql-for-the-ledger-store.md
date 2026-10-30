# ADR-006: PostgreSQL as the ledger's store

- **Status:** Accepted, resolved by [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)
- **Date:** 2026-08-29

## Context

The ledger owns accounts, transactions and balances. That data is relational, benefits from
real constraints, and needs exact decimal arithmetic — a floating-point representation of
money is a defect, not a trade-off.

This record was **Proposed** while `docker-compose.yml` ran a Postgres container that nothing
connected to and `ledger/pom.xml` deliberately carried no driver. It was ratified on
2026-09-06, when the persistence layer was written; the access strategy it left open is
decided in [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md).

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

## Resolved

The access strategy was deliberately not bundled into this ADR, and needed its own record
before the first entity was written. It has one:
[ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) chooses hand-written SQL over
`JdbcClient`, Flyway migrations, and a PostgreSQL schema per bounded context — and says why
JPA, Spring Data JDBC and jOOQ were each turned down. The migration discipline this record
called for lands with it, as does [ADR-020](ADR-020-audit-trail-follows-persistence.md)'s
append-only obligation.

## Alternatives considered

- **SQLite** — sufficient for a single-user tracker and far simpler to operate, but it
  gives up concurrent writers and the option of running the services on separate hosts,
  both of which the split in [ADR-001](ADR-001-polyglot-java-python-split.md) assumes.
- **A document store (MongoDB and similar)** — the data is relational and constraint-heavy;
  a schemaless store would move invariant enforcement entirely into application code, which
  is the opposite of this project's stated direction.
- **An event-sourced ledger** — a genuinely good fit for double-entry accounting and worth
  revisiting, but a large commitment to make before a single requirement is implemented.
