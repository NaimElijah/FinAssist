# ADR-024: Hand-written SQL over `JdbcClient`, and an append-only journal schema

- **Status:** Accepted, extended by ADR-033 and ADR-035
- **Date:** 2026-09-06

## Context

[ADR-006](ADR-006-postgresql-for-the-ledger-store.md) chose PostgreSQL and deliberately did not
choose how the ledger would talk to it: *"JPA with Hibernate, Spring Data JDBC, and jOOQ are all
live options, and the choice interacts with how much of the domain the persistence layer is
allowed to see. It needs its own ADR before the first entity is written."* This is that record,
and ADR-006 moves to Accepted with it.

Three Accepted decisions constrain the answer before it is chosen.

**[ADR-020](ADR-020-audit-trail-follows-persistence.md) attaches a precise obligation to this
commit:** *"the mapping must preserve the journal's append-only character — specifically, no
`UPDATE` or `DELETE` on a recorded transaction's entries, and lifecycle changes recorded as new
state rather than as an overwrite. That is a property of the schema and its migrations, so it must
be decided with them and not retrofitted."*

**[ADR-021](ADR-021-application-layer-shape.md) already shaped the ports for it.** No repository
port exposes a delete, and `save` is the only writer. The durable adapter inherits a contract that
cannot express a destructive write; what it does not yet inherit is a database that refuses one.

**[ADR-022](ADR-022-ledger-composition-root.md) constrains where shared code may live.** The slice
rule matches `io.github.naimelijah.finassist.ledger.(*)..` with no layer qualifier, so a common
`ledger.persistence` package would be a slice every context depends on, and would fail the build.

The aggregates are Java records with sealed lifecycle interfaces — `TransactionState` has four
variants, `PlanState` four, `Recurrence` three — and `domain_depends_on_no_framework` keeps every
annotation out of them.

## Decision

**Spring's `JdbcClient` with hand-written SQL.** Every statement the ledger issues is written in
this repository and readable in one place. That is what makes ADR-020's obligation checkable by a
person: `JdbcTransactionRepository` contains no `UPDATE` and no `DELETE`, and you can confirm that
by reading it.

**Rows are mapped to aggregates by hand, per context.** An entity is not an aggregate (ADR-007),
and here there is no entity at all — a row becomes a record through a `*Rows` mapper beside the
repository. The `Money` to (`numeric`, `currency`) conversion is **duplicated in each of the three
contexts, deliberately**: the slice rule leaves no legal shared home, `shared` is exempt only as a
target and ADR-021 already refused to let coupling accumulate there, and the conversion is two
lines each way.

**Flyway, with one migration directory per context** under `db/migration/<context>/`, each context
owning a major version so two can never collide or share a file. Each migration creates its own
schema, so `accounting`, `budgeting` and `planning` are three PostgreSQL schemas in one database.
**No foreign key crosses a schema.** This answers the requirements' open question — *"does planned
data belong in the same store as the books?"* — with: the same database, never the same schema,
and never a join. `SchemaBoundariesIT` asserts it against `information_schema`, so the rule is
enforced rather than intended, exactly as the ArchUnit slice rule is on the Java side.

**Money is `numeric` with no declared precision.** PostgreSQL's unconstrained `numeric` preserves
the exact scale it is given, which is what `Money` requires: its constructor calls
`setScale(currency.minorUnits(), UNNECESSARY)` and *rejects* excess precision rather than rounding
it away. JPY is scale 0 and the other four are scale 2, so a fixed `numeric(19,2)` would be a
latent defect rather than a constraint.

**The journal is append-only in the database, not merely in the adapter.** A
`BEFORE UPDATE OR DELETE ... FOR EACH ROW` trigger on `accounting.journal_transaction`,
`accounting.journal_entry` and `accounting.journal_transaction_state` raises an exception naming
the operation and this decision. A transaction's lifecycle is stored as rows in
`journal_transaction_state`, one per transition, and the current state is the highest `sequence` —
so Pending to Cleared to Reconciled is three inserts and no update. The
`(transaction_id, sequence)` primary key doubles as optimistic concurrency: two racing transitions
collide and one fails, rather than one silently overwriting the other.

**`budgeting` and `planning` are ordinary mutable tables.** ADR-020 binds *the journal*, and
[ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md) draws the line this rests on: `planning`
holds what the owner **intends**, `accounting` holds what **happened**. An audit trail is a record
of what happened. Giving plans append-only storage would be inventing a requirement nothing states,
and the no-delete port contract already keeps a cancelled plan as history.

**The database enforces what the domain enforces, so a bug in one is caught by the other** — the
pairing ADR-006 asked for. `CHECK` constraints mirror each sealed type variant for variant, so an
illegal state combination is unrepresentable in a row as well as in Java; a unique index on the
normalised account name is what makes `AccountRepository.save` atomic, as its Javadoc has promised
since ADR-021; and a `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks that a transaction's
entries sum to zero at commit.

**Transaction demarcation lives on the adapter.** `@Transactional` cannot go on an application
service — `the_application_layer_depends_on_no_framework` fails the build for it — and an adapter
is free to use Spring. `save` is where atomicity is actually needed: a transaction, its entries and
its state row land together or not at all.

## Consequences

**Makes easy**

- Reviewing the append-only guarantee. It is a trigger in a migration and an absence of statements
  in one class, both of which a reader can check.
- Mapping the sealed hierarchies honestly. A discriminator plus `CHECK` constraints says exactly
  what the Java type says, which an ORM's inheritance strategies would have obscured.
- Replacing a query. The SQL is the thing, not a derived artefact of an annotation.

**Makes hard**

- More code than an ORM for the simple cases. Six repositories and three mappers are written and
  maintained by hand, and a new column is touched in several places rather than one.
- The `Money` mapping exists three times. If the encoding ever changes it changes three times, and
  only a test will notice if one is missed.
- No dirty checking, no lazy loading, no identity map. All three are absent on purpose, and all
  three are things a contributor arriving from JPA will expect.
- Docker becomes a requirement to run the ledger's test suite. See
  [ADR-025](ADR-025-integration-tests-on-testcontainers.md).

**Accepted cost**

- `TRUNCATE` is not blocked. The triggers refuse `UPDATE` and `DELETE`, which is what ADR-020 names,
  but a statement-level truncate trigger would leave the integration tests unable to isolate
  without rebuilding the schema per test. `TRUNCATE` requires table ownership, which the eventual
  production role will not hold, and the revoke below is the real answer.
- A superuser can disable a trigger. This is a single-owner system with one writer; that is the
  threat model, and it is stated here rather than implied.
- **"Who" is still absent**, exactly as ADR-020 predicted: there is no authentication, so there is
  no actor column. Nothing here changes that, and ADR-020's revisit trigger stands.

## Alternatives considered

**JPA with Hibernate.** The option `LedgerArchitectureTest`'s own Javadoc anticipates ("JPA entities
belong in the outbound adapter and are mapped"). Rejected because dirty checking emits an `UPDATE`
that nobody wrote: the append-only obligation would become a property of how carefully entities are
detached, rather than of the schema, which is the opposite of what ADR-020 requires. It would also
introduce a second object model that reads like the aggregate and is not one — the confusion ADR-007
exists to prevent — and the sealed hierarchies map badly onto its inheritance strategies.

**Spring Data JDBC.** Aggregate-oriented and the closest fit on paper, since its aggregate root maps
onto ours. Rejected on a specific, disqualifying behaviour: saving an aggregate with a one-to-many
deletes every child row and re-inserts it. That is a `DELETE` on a recorded transaction's entries on
every save, which is the exact statement ADR-020 forbids. A repository whose normal operation
violates the audit requirement cannot be configured back into compliance.

**jOOQ.** Type-safe SQL checked against the real schema at compile time, and genuinely the strongest
option for correctness. Rejected on build cost: its code generation needs a live database during
`mvn compile`, which puts Docker in the path of every compilation rather than only the integration
tests, and adds a generated-sources tree to a repository whose whole style is that you can read what
runs. Revisit if the SQL surface grows past a few dozen statements.

**One shared schema with prefixed table names.** Simpler to query across, and rejected for that
reason: nothing would stop a later migration adding a foreign key between contexts, so the isolation
the slice rule enforces in Java would have no counterpart in the database.

**A database per context.** The truest reading of "extractable by moving its directory", and real
operational weight — three pools, three Flyway configurations, no single transaction — for a
single-owner system. Rejected as premature; the schema split preserves the option.

**`REVOKE UPDATE, DELETE` instead of triggers.** Rejected *as the primary mechanism* because in
Compose and in every developer database the application role owns the tables, and an owner can grant
the right back to itself; the revoke would look like enforcement and be none. It is the right
complement once a distinct, non-owning application role exists, which is a revisit trigger below.

## Revisit when

- **A distinct application role exists** — add `REVOKE UPDATE, DELETE, TRUNCATE` on the journal
  tables from it, so the trigger stops being the only thing standing there.
- **Authentication is decided** — ADR-020's "who" column becomes implementable and the journal
  schema gains an actor.
- **The SQL surface grows past a few dozen statements**, or a query is got wrong in a way a compiler
  would have caught. That is the threshold at which jOOQ stops being heavier than the problem.
- **A use case must write across two aggregates atomically.** Demarcation then moves off the adapter
  and into a decorator bean in `composition`, which is the only package that may see both an in-port
  and the framework.
- **A second writer to the ledger appears.** ADR-020 already names this as the event that breaks the
  assumption the journal alone is a sufficient record.
