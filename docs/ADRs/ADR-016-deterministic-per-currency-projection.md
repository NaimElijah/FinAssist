# ADR-016: Projections are deterministic, per currency, and stepped at an explicit cadence

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

[ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md) established the `planning` context.
This record decides how a projection actually computes, which is three separate questions that
all had cheap wrong answers available.

**How much uncertainty to model.** "Simulate future state" spans a deterministic line through a
single set of assumptions, a band of low/expected/high cases, and a Monte Carlo fan chart over a
return distribution.

**What to do about currencies.** A timeline of balance across accounts collides with
[ADR-012](ADR-012-multi-currency-without-conversion.md): with an ILS account and a USD account
there is no single line, and no rate with which to make one. Combining cash with investments has
the same problem when they are priced differently. ADR-012's own revisit trigger names "a
net-worth figure" as the point at which full FX stops being over-engineering, so this was a
genuine fork.

**How growth compounds.** An owner thinks in annual rates; a projection steps monthly.
Converting between them needs a twelfth root.

## Decision

**Deterministic.** Explicit assumptions in, one reproducible line out. There is no randomness in
the domain, seeded or otherwise.

**One currency per projection.** ADR-012 stands unchanged: no exchange rates, no conversion, no
consolidated figure. `Scenario` carries a currency and refuses a flow in another; `Projection`
refuses an opening balance in another; `Comparison` refuses to compare across currencies. An
owner holding two currencies gets two timelines and no combined total. `BalancePoint.total()`
adds cash to investments only because both are, by construction, in the same currency.

**The growth rate is per projection step, not per year.** `GrowthAssumption.perStep("0.005")`,
applied once per step. There is deliberately no `annual(...)` factory: `BigDecimal` cannot take a
twelfth root exactly, so such a factory would have to round silently and every figure downstream
would inherit an approximation nobody chose. `Money`'s constructor already refuses to make that
kind of decision for the caller; so does this. A caller thinking annually converts visibly, where
the choice is reviewable.

**Growth is applied and rounded at every step**, rather than compounded exactly and rounded once
at the end. Every point in a projection has to be an amount that could appear in an account, and
consecutive points have to agree with each other.

**A horizon must be a whole number of steps.** A ragged horizon would leave a final part-step
whose flows belong to no point, and dropping them silently is the failure a forecast cannot
recover from. `Projection.of` refuses it and says so.

**Points run from `Horizon.from()` to `Horizon.to()` inclusive**, while the horizon itself stays
half-open. The half-open span decides which dates a flow falls on; the points report the state at
each boundary of that span, including the one that closes it. Without the closing point, the end
state — the figure most comparisons turn on — would be the one thing a projection never showed.

Within a step, **growth applies first, then the step's flows land**. A contribution therefore does
not compound in the step it arrives in.

## Consequences

**Makes easy**

- Every figure is explainable and reproducible: it follows from stated assumptions and recorded
  plans, with nothing sampled. That matters in a system whose assistant must cite evidence for
  the numbers it reports ([ADR-014](ADR-014-grounded-answers-require-citations.md)).
- The whole projection is a pure function over immutable values, tested by calling it — the
  compounding cases assert exact amounts rather than approximate ones.
- ADR-012 needs no amendment, and `Money` enforces the currency rule for free.
- Stochastic modelling remains available later: the `Scenario → Projection` shape takes it
  without a rewrite.

**Makes hard**

- **No net-worth figure, still.** A multi-currency owner gets several charts and must add them up
  in their head, which is exactly the thing they wanted a tool for. This is the sharpest cost
  here and it is a deliberate one.
- A single deterministic line **understates uncertainty**, and reads as more confident than it
  is. A 7% assumption produces a precise-looking number for 2056 that is precise about nothing.
- Rounding at every step accumulates drift over a long horizon — a few minor units over three
  hundred monthly steps — where a single exact compounding would not.
- Per-step rates are less natural than annual ones, and pushing the conversion to the caller
  means it will be done inconsistently until something above the domain standardises it.
- The whole-number-of-steps rule makes "project to the end of next March" refuse rather than
  approximate, which will read as pedantry before it reads as safety.

## Revisit when

**Introduce FX** at the first genuine need for a consolidated cross-currency figure — the trigger
ADR-012 already names. It supersedes ADR-012 rather than this record, and nothing here blocks it:
a projection already carries its currency.

**Add scenario bands** — the same deterministic projection under low, expected and high
assumptions — as the cheap next step if a single line proves misleading in use. It needs no
probability model, no randomness, and three calls to a function that already exists.

**Reach for Monte Carlo** only with a real return model and a reason, and note the tension with
the "not financial advice" non-goal: a percentile fan chart invites exactly the reliance the
project says it does not offer.

**Offer an annual-rate factory** once there is a defensible convention for the conversion, stated
in one place rather than repeated at each call site.

## Alternatives considered

- **Monte Carlo simulation from the start** — the most honest picture of uncertainty, and
  rejected as premature: it needs a return model that does not exist, seeded randomness, and
  materially more test surface, to produce output that is easy to over-trust.
- **Low/expected/high bands now** — genuinely cheap and a real improvement on one line. Deferred
  rather than rejected; it is the first item under "Revisit when" for that reason.
- **Full multi-currency projection with conversion** — the only way to get a net-worth line.
  Rejected here because it is ADR-012's decision to overturn, not this one's, and doing it as a
  side effect of adding forecasting would bury a domain-wide change inside a feature.
- **An annual growth rate with a rounded twelfth root** — friendliest to callers, and rejected
  because it hides an approximation about someone's money inside a factory method, which is the
  precise failure mode `Money` was written to prevent.
- **Compounding exactly and rounding once at the end** — less drift over long horizons, and
  rejected because intermediate points would then be amounts no account could hold, and
  consecutive points would not add up.
- **Allowing a ragged horizon and dropping the tail** — friendlier, and rejected because the
  flows dropped are invisible and the resulting figure is confidently wrong.
