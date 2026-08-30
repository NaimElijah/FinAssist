# ADR-015: `planning` as a sixth bounded context, in the ledger

- **Status:** Accepted
- **Date:** 2026-08-30

Extends the context map of [ADR-010](ADR-010-bounded-context-map.md), which listed five
contexts. This adds a sixth. Nothing in that record is retracted.

## Context

Three capabilities were asked for together: recording planned purchases with a timeline of
balance, simulating current and future state across cash and investments, and comparing
financial strategies. They are one capability — forecasting — and none of the five existing
contexts holds it.

`accounting` is the obvious wrong home. [ADR-011](ADR-011-double-entry-bookkeeping.md) exists
so the books record **what happened**; a planned purchase is a statement about what has *not*
happened. Put the two in one package and the first query that sums "transactions" is wrong, in
a way that looks entirely reasonable in the code. `budgeting` answers a different question —
what may be spent per category in a period — and `portfolio` is about lots actually held.

So: a new context. The remaining question was which service owns it, and that is where this
record has something to admit.

[ADR-007](ADR-007-asymmetric-hexagonal-architecture.md) puts tactical DDD in the ledger
*because the ledger is invariant-dense*, and warns that applying it where invariants are absent
yields "anemic classes wrapping dataframes, plus mapping layers that map nothing". By that
criterion alone, forecasting belongs in the Python assistant: projection and comparison are
pure functions over immutable values, exactly like `portfolio`.

## Decision

**`planning` lives in `backend/ledger`, as a sixth context with its own hexagon.**

The owner chose the ledger over the assistant, for two reasons that ADR-007's criterion does not
weigh: a planned flow has a lifecycle worth tracking, and plans will need to persist, which the
ledger will have and the assistant will not.

That choice puts an obligation on the design, and the obligation is met rather than assumed.
`planning` earns its aggregates:

- **`PlannedFlow`** is an aggregate root with a real lifecycle — `Planned → Committed →
  Realised`, with `Cancelled` reachable from the first two. `Realised` is frozen and declares no
  transition method, so the freeze holds at compile time as well as at run time. This mirrors
  `TransactionState` deliberately: two lifecycles that behave differently for no reason are two
  things to remember.
- **The amount is positive and the `Direction` carries the sign.** A planned purchase of minus
  fifty is not constructible. This is the opposite choice from `Entry`, whose amount is signed
  because double entry needs entries to sum to zero, and the difference is a decision rather than
  an inconsistency.
- **`Projection` is derived and never stored**, exactly as `Balance` is. It is built by
  `Projection.of` from an opening balance and a `Scenario`; there is no way to set one point to a
  figure of your choosing and have the rest agree.
- **`Comparison` refuses** projections in different currencies, over different horizons, or at
  different step cadences, rather than aligning them approximately.

**`planning` names no accounting type.** Not `AccountId`, not `TransactionId`, not `Balance` —
`contexts_do_not_depend_on_each_other` fails the build for any of them, which was confirmed by
planting the import and watching it break. A projection therefore takes its opening balance as
`Money`, the way `unrealised_gain` takes a unit price rather than a `Quote`. The application
layer, which may see both contexts, is what will supply it.

The domain deliberately does **not** model which account a flow draws on, nor a link from a
realised plan back to the transaction that realised it. Both need a local reference type
translated at the boundary, and inventing one before a caller exists is speculation.

## Consequences

**Makes easy**

- Plans and facts cannot be confused, summed, or migrated into each other by accident, because
  they are in different contexts with different types and a build rule between them.
- The three requested capabilities are one vocabulary: a flow, a scenario, a projection, a
  comparison. Adding the second and third needed no new concepts beyond `GrowthAssumption`.
- `planning` inherited every architecture rule without a line of test being edited — the
  ArchUnit patterns match `ROOT.(*)..`. All three relevant rules were confirmed to fail against
  a planted violation and pass after reverting it.

**Makes hard**

- **This is the second lifecycle in the ledger, and it is nearly the same shape as the first.**
  Two sealed state interfaces with parallel transitions will drift if only one is maintained.
- It sits in the service whose job is recording truth, which is a standing invitation to let a
  plan leak into an accounting query once the application layer exists.
- The ledger now carries three contexts, and a forecasting one is the least invariant-dense of
  them. If the lifecycle turns out to be unused, ADR-007's warning applies to this record.
- The opening balance arriving as a bare `Money` means the application layer must get the
  currency right; the domain can only refuse a mismatch, not prevent one being offered.

## Revisit when

**Move `planning` to the Python assistant if its lifecycle goes unused.** If in practice flows
are created and read but never committed, realised or cancelled — if `PlanState` is only ever
`Planned` — then this context is pure functions in aggregate clothing, ADR-007's criterion wins,
and the move is a directory and a translation layer rather than a rewrite.

Merge `planning` into `budgeting` if the two turn out to change together every time; ADR-010
already names that as the trigger for merging any two contexts.

Add a local `AccountRef` and a realising-transaction reference the first time the application
layer genuinely needs them, not before.

## Alternatives considered

- **The Python assistant** — favoured by ADR-007's own criterion, since projection and
  comparison are pure functions with no identity and no lifecycle, and it matches `portfolio`
  exactly. Rejected by the owner because a planned flow has a lifecycle worth tracking and plans
  will need persistence the assistant has no story for. Recorded here because it is the
  alternative most likely to be right later, and the revisit trigger above is aimed at it.
- **Fold into `accounting`** — no new context, and a planned purchase looks a lot like a
  transaction. Rejected as the most expensive mistake available: it puts intentions and facts in
  one package, and the first aggregate query over them is silently wrong.
- **Fold into `budgeting`** — both are about money not yet spent, so the overlap is real.
  Rejected because a budget is a recurring cap on a category and a plan is a dated intention with
  a lifecycle; sharing a package would give "limit" and "planned" one vocabulary and two
  meanings.
- **No new context; a reporting query over existing data** — cheapest, and it collapses at the
  first planned purchase, which is data that exists nowhere in the system and has to be stored
  somewhere.
