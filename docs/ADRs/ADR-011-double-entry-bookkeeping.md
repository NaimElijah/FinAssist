# ADR-011: Double-entry bookkeeping, five account types, and a transaction lifecycle

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

[ADR-001](ADR-001-polyglot-java-python-split.md) put the ledger in Java because it is the half
of the system that carries invariants, and [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md)
gave it tactical DDD for the same reason. Neither said what those invariants *are*. Until now
the ledger had none: the packages were empty and the architecture rules were dormant.

A single-entry model — one transaction, one account, one amount — was the alternative. It is
simpler to model and simpler to enter by hand, but it leaves the domain almost nothing to
reject: a transfer becomes two unrelated rows that nothing ties together, and deleting one
leaves the books quietly wrong.

## Decision

**Double-entry.** `Transaction` is the aggregate root and holds two or more `Entry` values that
must sum to zero, in a single currency. The check runs in the compact constructor, so an
unbalanced transaction is never constructed — not persisted and rejected later, but never
brought into existence. Money cannot be created by recording something; it can only move.

**Five account types**, each carrying a normal balance: `ASSET` and `EXPENSE` increase with a
debit, `LIABILITY`, `INCOME` and `EQUITY` with a credit. Income and expense are *accounts*, not
labels, which is what lets an everyday purchase balance: buying coffee debits an expense account
and credits a current account. `EQUITY` is what an opening balance is posted against, so the
first transaction in a new ledger balances like every other one.

**Balance is derived.** `Balance.of(account, currency, transactions)` sums the entries. There is
no balance field on `Account` and there will not be one. Voided transactions keep their entries
as history but do not count.

**A lifecycle**, as a sealed interface: `Pending → Cleared → Reconciled`, with `Voided` reachable
from `Pending` or `Cleared`. `Reconciled` is frozen — it has been matched against a document the
bank also holds, so it is corrected with a reversing transaction, never edited.

That freeze is expressed by **absence**: `Reconciled` declares no transition method, so
`reconciled.clear(...)` does not compile. `Transaction` holds the state as the sealed interface
rather than a concrete type, so it also checks at run time and throws
`IllegalTransitionException`. Both guards exist because both call sites exist.

Every transition takes a date rather than reading a clock, so the domain stays pure and its
tests deterministic.

## Consequences

**Makes easy**

- The books are self-checking. A bug that would lose money makes a transaction refuse to
  construct, at the point of the mistake rather than in a report months later.
- A balance cannot drift from its entries, because there is nothing to drift.
- The sign of a figure has a defined meaning, so reports and the UI need no special cases.
- `money_is_never_floating_point` and `contexts_do_not_depend_on_each_other` became enforceable
  for the first time; `archRule.failOnEmptyShould=false` is deleted and the ledger's five rules
  are live.

**Makes hard**

- Every recorded purchase needs a category account to balance against, so a bare "I spent 12.50"
  is not expressible. The application layer will have to supply the counter-account, which is
  more work than a single-entry row.
- Four states and their transitions are more to hold in mind than an immutable record would be.
- Reconciled being frozen means a genuine mistake found late is corrected by a reversal, which
  is correct accounting and will feel like friction.
- No cross-currency transaction has a representation; see
  [ADR-012](ADR-012-multi-currency-without-conversion.md).

## Revisit when

The lifecycle, not the bookkeeping, is the part likely to move. Add a state if a real workflow
needs one — a `Scheduled` for future-dated transactions, say. Adding a state is additive; the
transitions are already explicit and the compiler will point at every switch that needs updating.

The double-entry decision is effectively not revisitable, which is why it was made before any
data existed. Retrofitting the balancing invariant onto single-entry rows means inventing a
counter-entry for every historical row and changing the aggregate root, and no migration makes
that additive.

## Alternatives considered

- **Single-entry** — one amount against one account. Simplest, and it was rejected because the
  domain would have almost nothing to enforce, which would make ADR-001's reason for choosing
  Java for this service hollow.
- **Single-entry now, double-entry later** — rejected explicitly. The migration is not additive
  (see above), so the cost of deferring is strictly higher than the cost of deciding now.
- **Assets and liabilities only, with categories as a separate concept** — closer to how consumer
  apps present things. Rejected: spending would no longer balance on its own, so either the
  invariant is dropped or a synthetic counter-account is invented anyway, which is the five-type
  model with worse names.
- **Descriptive account kinds** (`CHECKING`, `CREDIT_CARD`) instead of accounting types —
  rejected as the primary classification because they carry no debit/credit meaning. They remain
  useful as a second attribute alongside the type, and can be added without disturbing this.
- **An immutable transaction with no lifecycle** — genuinely defensible for manual entry, and
  rejected because it cannot represent a pending card charge, which is most of what a statement
  shows mid-month.
