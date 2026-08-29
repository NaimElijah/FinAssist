# ADR-013: Lot-based holdings with FIFO cost basis

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

The portfolio context has to answer "did I make money", not only "what do I hold". That needs a
cost basis, and the choice of basis decides what can be computed afterwards.

Three options: keep every purchase as its own lot and consume lots oldest-first; keep one
position per instrument with a running average cost; or track quantity only and give up on gain
entirely.

The choice interacts with tax. Some regimes require FIFO, others average cost, and the two give
different answers for the same trades — this is not a presentation detail.

## Decision

Each purchase is a `Lot`: quantity, unit cost, acquisition date. `consume_fifo(lots, quantity)`
sells oldest-first, splitting a lot that is only partly consumed so the remainder keeps its
original unit cost and date. It returns a `Disposal` — the remaining lots and the cost of the
units sold — from which `realised_gain(proceeds, cost_of_sale)` follows.

Everything is a pure function over immutable values. Selling returns a new tuple of lots and
never mutates the originals, which is asserted directly by a test. There are no aggregates here,
per [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md): the portfolio context protects no
invariant that needs one.

Selling more than is held raises `InsufficientHoldingError` rather than clamping to what is
available.

`unrealised_gain` takes a unit price as `Money`, **not** a `Quote`. A quote belongs to the
`marketdata` context, and importing one would couple two contexts.

## Consequences

**Makes easy**

- Realised gain is auditable: you can point at exactly which purchases a sale consumed, which is
  what a tax authority or a suspicious future owner will want.
- Pure functions over immutable lots are testable by calling them — no fixtures, no fakes, no
  container.
- Switching to average cost later is possible, because the lot detail it would need is recorded.

**Makes hard**

- More to model than a single averaged position, and the consumption logic has real edge cases:
  partial lots, exact exhaustion, selling everything.
- Lot ordering is the caller's responsibility. `consume_fifo` consumes in the order given, so a
  caller holding lots in some other order gets LIFO or worse without being told. This is
  documented, not enforced, and is the sharpest edge in the design.
- `InsufficientHoldingError` means an incomplete import surfaces as a failure rather than a
  quietly reduced number. That is the intent, and it will be inconvenient.

## Revisit when

Switch to average cost if the tax regime that actually applies requires it. That direction is
available — averaging lots is a fold over data already recorded.

The reverse is not. Averaging destroys the individual purchase history FIFO needs, so a system
that started with average cost cannot become FIFO for its existing data. That asymmetry is why
lots are recorded now even though the analysis using them does not exist yet.

Consider enforcing lot order inside `consume_fifo`, by sorting on `acquired_on`, the first time
a caller gets it wrong.

## Alternatives considered

- **Average cost** — one position per instrument with a running average. Materially less to
  model. Rejected because it is irreversible: the lot detail is gone, so the choice can never be
  revisited, and it is the wrong answer under a FIFO tax regime.
- **Quantity only, no cost basis** — answers "what do I own and what is it worth" and cannot
  answer "did I make money", which is usually the actual question. Adding a basis later would
  need purchase history that was never recorded.
- **Specific-lot identification** (the seller nominates which lots a sale consumes) — the most
  flexible and the most tax-efficient in some regimes. Rejected for now: it needs a user-facing
  choice at the moment of sale, and there is no interface to make it in. The lot model here is
  the prerequisite, so this stays available.
