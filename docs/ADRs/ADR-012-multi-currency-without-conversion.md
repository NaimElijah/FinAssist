# ADR-012: Multi-currency amounts, with no conversion

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

`Money` had to be written before anything else in the domain, and its shape decides whether a
currency can ever be got wrong. Three options were on the table: a single system-wide currency
with `Money` as a bare amount; amounts that carry a currency but never convert; or a full
model with exchange rates, conversion, and realised and unrealised FX gain as domain concepts.

The single-currency option is the smallest, and it breaks at the first foreign holding —
portfolio instruments are very often priced in a different currency from the account that
bought them. Retrofitting a currency afterwards means threading it through every signature.

## Decision

`Money` is an amount and a currency. `Account` has exactly one currency, and every entry must
match its account. Arithmetic is closed over a single currency: `plus`, `minus` and comparison
throw `CurrencyMismatchException` when the currencies differ.

**No exchange rates exist anywhere in the system.** There is no conversion, no reporting
currency, and no consolidated cross-currency total. A balance is per account, therefore per
currency.

The amount is normalised to the currency's minor units on construction. An amount carrying more
precision than the currency admits is **rejected, not rounded** — rounding at construction is a
silent decision about someone's money, and the caller should make it deliberately.

`Currency` is a hand-written enum rather than `java.util.Currency`, so it stays comparable with
the Python table required by [ADR-010](ADR-010-bounded-context-map.md); a JDK lookup on one side
and a table on the other would drift with nothing noticing.

## Consequences

**Makes easy**

- Adding two currencies is impossible rather than merely discouraged. There is no path by which
  a meaningless total reaches a report.
- The model is small: no rate source, no rate date policy, no question of which rate a figure
  used.
- Foreign accounts and foreign-priced holdings are representable from day one.

**Makes hard**

- **No net-worth figure across currencies.** With an ILS account and a USD account there is no
  single number for what the owner has, and producing one is not a small addition — it needs a
  rate source, a policy for which rate on which date, and an answer for how gains from rate
  movement are recorded.
- A cross-currency transfer has no representation. It is two transactions plus something to tie
  them together, and that something is not modelled.
- The Java and Python currency tables are duplicated and must be changed together.

## Revisit when

The first genuine need for a consolidated cross-currency total — a net-worth figure, or a
portfolio spanning currencies that must report as one. That is the point at which
[full multi-currency with FX](#alternatives-considered) stops being over-engineering, and it
will roughly double the accounting domain. Nothing decided here blocks it: `Money` already
carries the currency, so the change is additive rather than a rewrite.

## Alternatives considered

- **Single currency, fixed in configuration** — `Money` as a bare amount, trivially valid
  totals. Rejected because it breaks at the first foreign holding and the fix touches every
  signature in the domain.
- **Full multi-currency with exchange rates** — the correct model for genuinely multi-currency
  finances, with `ExchangeRate`, a conversion policy and FX gain and loss as ledger entries in
  their own right. Rejected as premature: it roughly doubles the accounting domain for a single
  owner who has not yet asked for a consolidated figure.
- **Rounding rather than rejecting an over-precise amount** — friendlier, and rejected because
  it makes a decision about money silently. A caller that wants rounding can round before
  constructing, where the choice is visible.
