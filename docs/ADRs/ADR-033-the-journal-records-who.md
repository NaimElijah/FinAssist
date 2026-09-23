# ADR-033: The journal records who

- **Status:** Accepted — its claim that `JournalActorMigrationIT` rehearses the owner's real
  upgrade is corrected by [ADR-035](ADR-035-flyway-runs-out-of-order.md)
- **Date:** 2026-09-22

## Context

[ADR-020](ADR-020-audit-trail-follows-persistence.md) named the gap plainly: *"'Who' is blocked
on authentication, not on persistence... It is listed here so that the authentication decision,
when it is made, knows it owes this record an actor."* [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)
repeated the same accepted cost when the schema landed: *"'Who' is still absent... there is no
authentication, so there is no actor column."*

Authentication now exists. [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md) makes
both resource servers validate a bearer token and deny by default; [ADR-032](ADR-032-role-based-authorization-per-service.md)
fixed `sub` as the principal Spring's `JwtAuthenticationConverter` exposes. Every request the
ledger now answers carries a real, validated identity. The trigger both earlier records named has
fired.

**Three constraints shape the answer.**

- **The owner's real database already holds rows written under `V1_1`.** Any schema change has
  to be safe for them: additive, no backfill, no rewrite. `accounting.refuse_rewrite()` already
  forbids an `UPDATE` on `journal_transaction` and `journal_transaction_state`, which rules out a
  backfill even if one were wanted — a historical row cannot be corrected, and inventing an actor
  for it would be worse than leaving it unrecorded.
- **[ADR-021](ADR-021-application-layer-shape.md) fixed that "identity is generated inside the
  service, never passed in."** An actor is not the transaction's identity; it is who is asking,
  which is a different fact from what is being recorded. The rule this ADR protects is that a
  caller cannot dictate a `TransactionId` or manufacture its own audit trail entry by naming one
  on the command — nothing here lets it do that. `Actor` travels beside `RecordTransactionCommand`
  and beside each `TransitionTransactionUseCase` method, never inside a value the command's own
  constructor could be tricked into trusting as identity.
- **[ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md) drew the accounting/planning line
  on exactly this basis**: accounting holds what happened, planning holds what is intended. An
  audit trail is a record of what happened. `budgeting` and `planning` have no comparable
  requirement and gain no actor column.

## Decision

**`accounting.journal_transaction` and `accounting.journal_transaction_state` each gain a
nullable `recorded_by text`, added by `V1_2__journal_recorded_by.sql`, with no default and a
`CHECK (recorded_by IS NOT NULL AND btrim(recorded_by) <> '' AND char_length(recorded_by) <= 255)`
added `NOT VALID`.**

- **No default.** A default would stamp a false recorder onto every row already in the table the
  moment the column appeared.
- **`NOT VALID` is not a shortcut; it is the only legal option.** It tells PostgreSQL to skip
  scanning the rows already there, so the historical `NULL`s are simply never checked against the
  constraint, while every row written from this moment on is still checked as it is written. The
  append-only triggers make `VALIDATE CONSTRAINT` impossible to run later and have it succeed: a
  validation scan would find the historical `NULL`s, refuse to validate, and the fix a validation
  failure would demand — updating those rows — is exactly what the triggers exist to refuse. The
  migration's own comment says so: **never validate these constraints.**
- **`ADD COLUMN` is DDL and fires no row trigger.** Confirmed against the real schema by
  `JournalActorMigrationIT`, not assumed: it seeds a transaction and its state rows under a
  database migrated only to `1.1`, then migrates to `latest`, and the migration succeeds with
  every historical row intact.

**`accounting/application/port/in/Actor`** is a plain record holding a non-blank `subject` of at
most `Actor.MAX_SUBJECT_LENGTH` (255) characters, validated in its constructor — the same limit
the migration's `CHECK` enforces, kept in sync by review since no constant can be shared across a
Java class and a SQL constraint.

**An actor is not an identity accepted on a command — ADR-021's rule still holds.** `Actor`
travels alongside `RecordTransactionCommand` and alongside each of
`TransitionTransactionUseCase`'s methods, never as a value a command's constructor treats as the
transaction's own identity. `TransactionId` is still always generated inside the service. What a
command carries is still exactly what its caller supplied to describe the fact being recorded;
`Actor` describes who is asking, a different question, answered by a different party — the
composition root's security chain, never the caller's own input.

**`TransactionController` builds the `Actor` from `java.security.Principal#getName()`.** The
parameter type is the JDK's own `java.security.Principal`, never a Spring Security type, so the
`accounting` context still imports no framework. `LedgerSecurityConfiguration` sets `sub` as the
principal claim (`JwtAuthenticationConverter#setPrincipalClaimName`), so `getName()` is the
validated token's subject. springdoc 3.1.1's `AbstractRequestService` lists `java.security.Principal`
in its default `PARAM_TYPES_TO_IGNORE`, so the parameter never reaches the published contract —
confirmed by disassembling the installed jar, not assumed, and proven by `LedgerContractIT`
showing no diff.

**`TransactionRepository.save` takes the actor alongside the transaction.** The transaction row's
`recorded_by` is written once, on the insert that first records that identity; each later call —
a lifecycle transition — writes the actor it was given onto the new `journal_transaction_state`
row it appends, which may name someone other than whoever first recorded the transaction.
`JdbcTransactionRepositoryIT` proves this directly: a transaction recorded by one subject and
cleared by another stores each writer on its own row, and the transaction row's own `recorded_by`
never changes.

**Budgeting and planning stay unattributed.** Neither gains a `recorded_by` column, a `Budget`
has no lifecycle to attribute, and a `PlannedFlow` is intent rather than fact (ADR-015). Extending
this decision to either would be inventing a requirement nothing states.

**The recorded value is stored, not yet exposed.** No response DTO carries `recorded_by`; no
endpoint reads it back. Surfacing it is [#63](https://github.com/NaimElijah/FinAssist/issues/63).

## Consequences

**Makes easy**

- Answering "who wrote this row" for everything recorded from today onward, with a value a
  reader can trust: it is the token's own `sub`, never something the request body could set.
- Confirming the guarantee by reading two files: the migration's `CHECK`, and the absence of any
  `UPDATE` in `JdbcTransactionRepository` that could apply a value after the fact.
- Telling a lifecycle transition's writer apart from the transaction's original recorder, since
  each row keeps its own.

**Makes hard**

- A query that wants "who touched this transaction, ever" has to read every
  `journal_transaction_state` row, not just the transaction row — there is no single denormalised
  answer, on purpose: each row is honest about only what it itself recorded.
- Every existing test and fake that recorded or transitioned a transaction now supplies an
  `Actor`. That surface (`RecordTransactionCommand`, `TransitionTransactionUseCase`,
  `TransactionRepository.save`) was already the smallest one available; it grew by one parameter
  each, not by a new port.

**Accepted cost**

- **Rows written before `V1_2` honestly name nobody, forever.** They cannot be backfilled — the
  append-only triggers forbid the `UPDATE` a backfill would need — and that is the correct answer,
  not a defect to schedule against. A `NULL` here means "before this ledger had an authenticated
  caller," which is true and worth keeping true.
- **The value is not yet visible to anyone but the database.** Until #63, "who recorded this" is
  answerable only by a person with `psql` access, which is the same audience ADR-020 already
  accepted for the append-only guarantee itself.

## Alternatives considered

**A `NOT NULL` column with a placeholder subject such as `"system"` for historical rows.**
Rejected. It would assert a fact that is false — no such caller existed — and a future reader
would have no way to distinguish an honest placeholder from a real subject that happened to be
named `"system"`.

**Validating the constraint once, accepting that historical rows would need a value.** Rejected
outright: the append-only triggers make this impossible to execute (the `UPDATE` a validation
failure implies is refused by the database itself), and even if it were possible, it would be the
backfill this record explicitly rejects.

**A separate audit table recording `(transaction_id, actor, at)` rather than a column on the
existing rows.** Rejected on the same grounds ADR-020 rejected a parallel audit log: the journal
is the audit record, and a second table recording facts about it would drift from it the moment
anyone forgot to write to both.

**Putting `Actor` inside `RecordTransactionCommand`'s own identity, or generating it from the
command.** Never seriously considered — this is exactly the shape ADR-021 already forbids. An
actor is supplied by the composition root's security chain from a validated token, not
constructed by or accepted from the caller's own input.

**Extending `recorded_by` to `budgeting` and `planning`.** Rejected: neither holds a lifecycle
that ADR-020's audit requirement was ever about, and ADR-015 already puts intent in a different
context from fact for exactly this reason. Revisit only if either context grows a fact-recording
responsibility of its own.

## Revisit when

- **#63 exposes `recorded_by` through the API.** This record's "stored, not yet exposed" clause is
  discharged then, and this ADR should be extended rather than superseded.
- **A distinct, non-owning application role exists** (ADR-024's own revisit trigger). The
  append-only guarantee moves from trigger-only to trigger-plus-`REVOKE`, and this record's claim
  that a backfill is impossible should be re-checked against the new privilege model.
- **`budgeting` or `planning` grows a lifecycle it did not have when ADR-015 drew this line.**
  That is the moment to ask whether either needs its own actor column, not before.
