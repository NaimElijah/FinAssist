# ADR-066: The ledger's store holds every rule the domain holds across rows

- **Status:** Accepted
- **Date:** 2026-10-31

Extends [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md), whose schema made the
journal append-only against `UPDATE` and `DELETE`, and held each rule a single row can express. It
also extends [ADR-020](ADR-020-audit-trail-follows-persistence.md), whose audit record is the
journal those triggers protect.

## Context

**Scan 6 of 12 read the ledger's schema, migrations and queries on 2026-10-31**
([#343](https://github.com/NaimElijah/FinAssist/issues/343)). It ran every finding against a
throwaway PostgreSQL 18.6 seeded with ten years of synthetic books: 60,000 transactions, 120,000
entries and 143,553 state rows. ADR-006 asked for constraints "so that a bug in one is caught by the
other", and ADR-024 says the database enforces what the domain enforces. The scan found that this
held for most rules a single row expresses, and for few that span rows. One single-row gap is
left for another change: a text column's `btrim` check admits a value the domain finds blank, such
as a tab ([#402](https://github.com/NaimElijah/FinAssist/issues/402)).

- **A recorded transaction could gain new entries.** The triggers refused `UPDATE` and `DELETE`
  only. A balanced pair inserted into a reconciled transaction was accepted, and the account's
  balance moved, with no state row and no record of who did it. `requirements.md` says a
  recorded transaction is never rewritten.
- **The state history accepted any state at any sequence.** A `PENDING` row inserted after
  `RECONCILED` un-reconciled the transaction. `requirements.md` says only legal transitions occur,
  a reconciled transaction is frozen, and a transaction moves forward in time.
- **A header could exist with no entries and no state.** Every read ignores such a header, and its
  id can never be recorded again.
- **Nothing tied a header's currency to its entries' currency.**
- **An account's currency could change under its entries.**
- **A budget limit, and a strategy's flow, could be stored in a currency other than their
  parent's.** The repositories' own comments said no constraint could tie two tables' currencies.
  A composite foreign key can.
- **The balance refusal printed the sum of the entries** into a message that reaches the log.

Each of these could be reached only by a writer outside the adapters, such as the owner in `psql`,
a restored dump, or a future adapter with a bug. That is the writer ADR-006's pairing exists for.

## Decision

**Each rule the domain holds across rows is held by the store too, by a constraint where one can
express it and by a trigger where only a trigger can.** Every change is a new migration under its
context's major version (ADR-035). Each is pinned by an IT that was red before the migration
existed. One cross-row rule the domain holds is left open on purpose: `Budget` refuses an empty
set of limits and the store does not, because whether an empty budget is legal is the owner's
decision ([#401](https://github.com/NaimElijah/FinAssist/issues/401)).

**The journal (`accounting`):**

- **`V1_3`: a transaction's entries close once it is recorded.** A `BEFORE INSERT` trigger on
  `journal_entry` refuses a row once the transaction has any state row. The adapter writes the
  header, the entries and state 0 in one database transaction, in that order, so it never reaches
  the refusal.
- **`V1_4`: the state history follows `Transaction`'s lifecycle.**
  - The first row is sequence 0, `PENDING`.
  - Each later row takes the next sequence.
  - `PENDING` moves only to `CLEARED` or `VOIDED`, and `CLEARED` only to `RECONCILED` or `VOIDED`.
  - No state is dated before the transaction occurred, or before the previous state.
  - The refusal names the transaction and the states, and no date.
- **`V1_5`: a header commits only whole.** A deferred constraint trigger refuses, at commit, a
  header with no entry or no sequence-0 state. `V1_1`'s balance check already refuses fewer than
  two entries once there is one, so the two hold "at least two" between them, each under its own
  message.
- **`V1_6`: an entry stays in its account's currency.** A unique index on `account (id, currency)`
  and a composite foreign key from `journal_entry (account_id, currency)`, added `NOT VALID` and
  never validated. It refuses every new entry in another currency. It also refuses a change to an
  account's currency once the account has entries, because a `NOT VALID` foreign key still checks
  an update to the row it references.
- **`V1_7`: entries are in the header's currency, and the refusal names no amount.**
  `refuse_unbalanced_transaction()` is replaced. It refuses entries whose currency differs from
  the header's, and says only that the entries do not sum to zero. Its check of an entry against
  its account goes, because `V1_6` refuses that entry before the deferred check runs.

**Budgeting and planning:**

- **`V2_2`: a budget limit is in its budget's currency.** A unique key on `budget (id, currency)`
  and a composite foreign key from `budget_limit (budget_id, currency)`, added validated. These
  tables are mutable, and Flyway holds the script's lock to commit either way.
- **`V3_2`: a strategy's flow is in the strategy's currency.**
  - `scenario_flow` gains a `currency` column, backfilled from `planned_flow`, then `NOT NULL`.
  - Composite foreign keys tie it to `scenario (id, currency)` and to `planned_flow (id, currency)`.
  - The adapter writes the column.
  - No use case can change a plan's or a strategy's currency, so neither key can refuse a
    legitimate edit.

## Consequences

**Makes easy**

- **A defect in any one writer cannot corrupt the books silently,** whether it is the domain, an
  adapter, a dump or a hand-typed statement. The store refuses what the domain refuses.
- **The race #370 tracks can no longer record an impossible history.** `V1_4` refuses a
  transition whose move is illegal from the state now stored, such as a reconcile landing after a
  void. A stale transition whose move is still legal, such as a void landing after a clear it never
  read, is admitted, because the history it leaves is one the domain allows. Carrying the
  sequence a transition was validated against, and answering 409, is still #370's.
- **No journal row is scanned or rewritten.** `V1_3` to `V1_7` only add triggers, an index on the
  small account table and a `NOT VALID` key. `V2_2` validates its key over every stored limit, and
  `V3_2` backfills, then validates, the mutable link table; both are small. `LedgerUpgradeIT`
  starts the ledger on a seeded history, including an entry whose account changed currency before
  `V1_6`.

**Makes hard**

- **A writer must insert the header, its entries and state 0 in one database transaction,** in that
  order. Each transition is saved as it happens, since jumping two moves is refused. The IT helpers
  that wrote a bare header in autocommit now write it whole.
- **A refusal raised at commit arrives wrapped.** Spring reports it as a
  `TransactionSystemException` with the database's message on its cause.
- **Some refusals are still unmapped.** An account re-saved in another currency, and a lost
  transition race, surface as a `DataAccessException`, which answers 500.
- **Re-saving a budget or a strategy in a new currency would now be refused,** because `save`
  upserts the parent while its children still name the old currency. No use case does it today. One
  that does must replace the children first.
- **`V1_6`'s foreign key is never validated,** for the same reason ADR-033 never validates
  `recorded_by`: a historical mismatch could not be repaired under the append-only triggers.
  `pg_dump` re-emits it `NOT VALID`.

## Alternatives considered

- **`REVOKE INSERT` after the first commit.** Rejected for the reason `V1_1` gives against `REVOKE
  UPDATE, DELETE`: the owning role can grant the right back to itself.
- **A validated foreign key on `journal_entry`.** Rejected: one historical mismatch would fail the
  owner's start-up, and the append-only triggers forbid the `UPDATE` that would repair it.
- **A deferrable foreign key.** Rejected: it would fire among the deferred triggers in an order
  nobody controls.
- **Triggers instead of a currency column on `scenario_flow`.** Rejected: one trigger for each
  direction, written by hand, where two declarative keys do the same.
- **A write timestamp on every journal row.** Not adopted. ADR-020 defines "when" as the
  business date. The scan raised it as a question, not a finding.

## Revisit when

- **A use case lets a budget's, a strategy's or an account's currency change.** Its `save` replaces
  the children first, or the keys refuse it.
- **#370 lands.** A stale transition is refused whatever its move, and the refusal becomes a 409
  instead of a 500.
- **A second writer of the journal appears,** such as an import. It writes whole transactions in
  one database transaction, or the triggers refuse it.

## What lands with this record

- **The migrations** `V1_3` to `V1_7`, `V2_2` and `V3_2`, with their digests appended to
  `ReleasedMigrationsTest`. `LedgerUpgradeIT` asserts the history `1.1, 2.1, 3.1, 1.2, 1.3 … 1.7,
  2.2, 3.2`.
- **The ITs:**
  - `JournalIsAppendOnlyIT`: the closed entries;
  - `JournalStateHistoryIT`: every refused transition, sequence and date, and the legal paths;
  - `JdbcTransactionRepositoryIT`: the complete header, the header's currency, and the message
    with no amount;
  - `JdbcAccountRepositoryIT`: an account's currency fixed under its entries;
  - `JdbcBudgetingRepositoriesIT` and `JdbcPlanningRepositoriesIT`: the database refusing each
    cross-currency row.
- **Documentation:**
  - `backend/ledger/CLAUDE.md`'s Persistence section;
  - the "Enforced by" cells in `docs/requirements/requirements.md`;
  - `docs/architecture/architecture.md` and `docs/testing/testing.md`;
  - `docs/operations/operations.md`'s upgrade note;
  - ADR-020 and ADR-024 read `extended by … ADR-066`, as do their rows in `docs/ADRs/README.md`,
    with a row and a note for this record.
