# ADR-075: A transition is stored only against the state it was validated on

- **Status:** Accepted, extends [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) and
  [ADR-066](ADR-066-the-store-holds-every-rule-the-domain-holds-across-rows.md)
- **Date:** 2026-11-05

Extends ADR-024, whose `(transaction_id, sequence)` primary key was meant to double as optimistic
concurrency, and ADR-066, whose `V1_4` trigger checks each state row against the state stored now
and left a stale move whose target is legal to land, "still #370's".

## Context

**A move is validated against one read, and the write is checked against a later one.** A use case
reads a transaction, asks the aggregate for the move, and saves the result. The adapter used to
re-read the current state at save time and append at the next `sequence`, so the state a move was
decided on and the state it was written after could differ. Scan 3 found it
([#340](https://github.com/NaimElijah/FinAssist/issues/340)), and
[#370](https://github.com/NaimElijah/FinAssist/issues/370) tracks it.

**What happened.** Two moves from one read, before the fix. Each database outcome was measured at
the adapter on this branch against PostgreSQL 18.6. Of the HTTP answers, the key collision's 500 was
measured through `AccountingHttpIT`; the rest follow from how the service and
`LedgerProblemHandler` treat each outcome.

| Case | Outcome |
|---|---|
| Clear, then void | Stored as `PENDING, CLEARED, VOIDED`: the voider never saw it cleared |
| Void then clear; reconcile then void; void then reconcile; clear then clear on a later date | `V1_4`'s trigger refuses, `P0001`, answered 500 |
| Clear then clear on the same date | Nothing stored, answered 200, and a second event published |
| A rival's row is uncommitted, then it commits | `23505` on the primary key, answered 500 |
| A rival commits between the worker's state read and its insert, at `READ COMMITTED` | `P0001` "the next state is sequence 2, not 1", answered 500 |

Three forces follow from the table:

- **#370's own proposal would not have met its acceptance.** It inserts at the next sequence and
  maps the key collision. The trigger fires before the key is checked, so a rival that has already
  committed yields `P0001` from the trigger, not `23505` from the key, and nothing maps `P0001`.
- **#370's example of a voided transaction left `CLEARED` was already closed** by `V1_4`, which
  refuses a clear after a void. What remained was the stale move whose target is legal, and every
  stale move that answered 500.
- **The journal is append-only (ADR-020).** A row stored on a stale decision is permanent: the
  history `PENDING, CLEARED, VOIDED` cannot be corrected, only read.

**Planning is worse off.** `JdbcPlannedFlowRepository`'s upsert has no trigger behind it, and
`planning.planned_flow` holds only the current state, because `planning` is an ordinary mutable table
(ADR-015, ADR-020). Measured the same way, on the code before the fix:

- A cancel and a commit decided on one read of a `Planned` flow, the commit written last, left the
  flow `Committed`, so `PlannedFlow.countsTowardsProjection` was true for a plan that had been
  cancelled, and nothing recorded the cancel.
- Over HTTP, a commit waiting on a rival's uncommitted cancel answered 200 and overwrote `CANCELLED`
  with `COMMITTED`.

And on the conditional `UPDATE` this record adopts, with the isolation forced to `REPEATABLE READ`,
the same wait raised `CannotAcquireLockException` ("could not serialize access due to concurrent
update"), which nothing maps.

## Decision

**The ledger stores a lifecycle move only if the state it was validated against is still the stored
one, and answers 409 `ILLEGAL_TRANSITION` when it is not.** Both halves of the decision are in this
record, accounting's and planning's.

**The journal (`accounting`):**

- **`TransactionRepository.saveTransition(validatedAgainst, next, recordedBy)`** runs at
  `REPEATABLE READ`. It reads the stored state, compares it with `validatedAgainst`, and inserts
  `next` at the current `sequence` plus one. A failed comparison and a `DuplicateKeyException` both
  become `ConcurrentTransitionException`, which `AccountingProblemAdvice` answers 409.
- **`REPEATABLE READ` puts the state read and `V1_4`'s trigger on one snapshot,** so a rival's
  commit after the read is invisible to both and can surface only as a key collision, the one
  failure the adapter catches. `P0001` then means a real defect, such as a caller storing two moves
  at once, and stays a 500.
- **States are compared; a sequence is not carried.** The lifecycle has no cycle (`V1_4`), so no
  state recurs and a stored state equal to the validated one means nothing has moved since: there
  is no ABA. `sequence` stays private to the adapter.
- **`save` records a new transaction only.** A changed state on a stored identity throws
  `IllegalStateException`, so no caller can append a state without the comparison.
- **The 409 reuses `ILLEGAL_TRANSITION`.** The client's next step is the same as for a forbidden
  move, to read the transaction again and decide; only the detail differs.

**Planning (`planning`):**

- **`PlannedFlowRepository.saveTransition(validatedAgainst, next)`** is one `UPDATE … WHERE id = :id
  AND state = :validatedAgainst`, and it writes the state columns only. Zero rows throws
  `ConcurrentPlanTransitionException`, answered 409 `ILLEGAL_TRANSITION` by `PlanningProblemAdvice`.
  The comparison is on the state's name alone: the lifecycle has no cycle, and a state's date and
  reason change only along with it. A flow that was never stored also updates zero rows and is
  refused the same way, because the port has no delete and its one caller has just read the flow.
- **A waiting `UPDATE` re-checks its `WHERE` against the row the rival committed,** so planning
  needs no isolation change and no transaction. The comparison is the statement's own `WHERE`, so
  there is no gap between a read and a write for a rival to land in.
- **`REPEATABLE READ` would be wrong here.** The same wait would raise a serialization failure
  (`CannotAcquireLockException`) instead of updating zero rows, and nothing maps it. The adapter
  therefore has no `@Transactional` and sets no isolation level, deliberately.
- **`save` becomes a plain `INSERT`,** for the same reason as the journal's: a state change has one
  door. A re-save of a stored identity raises `DuplicateKeyException`, which nothing catches and
  which answers 500; no use case does it.

**This record replaces `V1_4`'s header comment, lines 19 to 25.** They describe the old `save`:
that it "re-reads the current state and appends at the next sequence", that a legal stale move
"still lands", and that refusing it is #370's. None of that is true now. `V1_4` is a released
migration, pinned by `ReleasedMigrationsTest` and by Flyway's checksum, so the lines cannot be
edited. Read them as history.

## Consequences

**Makes easy**

- **`V1_1`'s comment and ADR-024's sentence that the primary key doubles as optimistic concurrency
  are now true** for overlapping transitions, and neither is edited.
- **No migration,** so the owner's backup ([#405](https://github.com/NaimElijah/FinAssist/issues/405))
  and [#440](https://github.com/NaimElijah/FinAssist/issues/440) are unaffected.
- **No web change:** both hooks already refetch on `ILLEGAL_TRANSITION`.
- **The contract is unchanged.** It has no 409, and `code` is a bare string, so `LedgerContractIT`
  passes untouched. #370 expected a contract change; there is none.
- **A rival's commit is a 409 problem, not a 500,** and a refused move publishes no event.

**Makes hard**

- **A transaction around the journal's `saveTransition` must itself be at `REPEATABLE READ`,**
  because Spring would otherwise run it at the outer level, silently, and bring back the `P0001`
  500 with every test green. The next wrapper is already planned: ADR-048's outbox
  ([#35](https://github.com/NaimElijah/FinAssist/issues/35)) writes in the aggregate's transaction,
  and ADR-024 sends such a boundary to a decorator in `composition`. So the transaction manager
  validates a join (`setValidateExistingTransaction`, in `LedgerConfiguration`): one at another
  level fails on its first call with `IllegalTransactionStateException`, and a wrapper has to
  choose its level. `JournalTransitionRaceIT` holds both outcomes.
- **A transaction around planning's `saveTransition` must not be above `READ COMMITTED`,** for the
  reason above. The guard cannot see this, because planning names no level, but `PlanningHttpIT`
  goes through any wrapper on the use case and would answer 500. So one decorator cannot serve both
  contexts' transitions at one level.
- **`JournalTransitionRaceIT`'s window test relies on the adapter reading the state before the
  entries.** Swapped, it would stay green and prove nothing about that window.
- **`V1_4`'s header lines 19 to 25 are untrue and cannot be fixed in place.** This record is where
  the correction lives.
- **Not closed: the window between a page's read and its `POST`.** The request body carries no
  expected state, so a person who opened a transaction, waited, and then voids it voids whatever it
  has become. Only the interval inside the server is closed.

## Alternatives considered

- **A row lock (`SELECT … FOR UPDATE` or `FOR NO KEY UPDATE`).** Rejected: it needs the `UPDATE`
  privilege, and [#34](https://github.com/NaimElijah/FinAssist/issues/34) plans a ledger role with
  only `SELECT` and `INSERT` on the journal.
- **`pg_advisory_xact_lock`.** Rejected: nothing enforces it, so a writer that does not take it
  still reaches `P0001`.
- **`SERIALIZABLE`.** Rejected: it adds serialization failures (`40001`) for no gain over
  `REPEATABLE READ` here.
- **`REPEATABLE READ` for planning too.** Rejected: measured above, the wait on a rival's update
  becomes a serialization failure that nothing maps, where a plain `UPDATE … WHERE` at `READ
  COMMITTED` already answers correctly.
- **A conditional `INSERT … SELECT`.** Rejected: the trigger's own snapshot, at `READ COMMITTED`,
  still raises `P0001` for a rival that commits mid-statement.
- **A trigger SQLSTATE in a new `V1_8`.** Rejected: an owner-run migration for what the isolation
  level gives for free.
- **Carrying `sequence` through the domain.** Rejected: the aggregates are records relied on for
  value semantics, and comparing states is enough.
- **A new problem code.** Rejected: nothing branches differently. **Revisit when** a client must act
  differently on a stale move than on a forbidden one.
- **`If-Match` or an expected state on the wire.** Rejected for now: it closes the interval between
  a page's read and its `POST`, and matters only once a second human writer exists. **Revisit when**
  that writer arrives (M6 or M7).

## Revisit when

- **A transaction boundary moves outside the adapter,** such as ADR-048's outbox
  ([#35](https://github.com/NaimElijah/FinAssist/issues/35)) or a tenancy setting
  ([#205](https://github.com/NaimElijah/FinAssist/issues/205)): it picks a level for each context,
  as above.
- **A transaction-pooling proxy appears (M5),** because pgjdbc sets the isolation level per session,
  and a proxy that shares a session between transactions would not honour it.
- **The lifecycle gains a cycle,** such as reopening a reconciled transaction: a state would recur,
  and comparing states would no longer detect that something moved in between.
- **A second human writer exists (M6 or M7).** Carry the expected state on the wire.
- **A client must act differently on a stale move than on a forbidden one.** Add a code.
