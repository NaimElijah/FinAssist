# ADR-041: `consume_fifo` orders its own lots, oldest first

- **Status:** Accepted
- **Date:** 2026-10-15

## Context

**[ADR-013](ADR-013-fifo-lot-based-cost-basis.md) chose a FIFO cost basis, and left the order to
the caller.** Its consequences say so: "`consume_fifo` consumes in the order given, so a caller
holding lots in some other order gets LIFO or worse without being told. This is documented, not
enforced, and is the sharpest edge in the design." Its last revisit trigger reads: "Consider
enforcing lot order inside `consume_fifo`, by sorting on `acquired_on`, the first time a caller
gets it wrong."

**[ADR-021](ADR-021-application-layer-shape.md) put the sort in the one caller there is.**
`DisposeUnits` sorted the lots before calling `consume_fifo`, so the use case was right. The
function was not. Anything reaching it past the use case still got whatever order it passed in.

**No caller has got it wrong yet, and one is about to be written.**
[#158](https://github.com/NaimElijah/FinAssist/issues/158) records a sale, which consumes lots
for real rather than pricing a disposal. A second caller written against the domain function is
exactly the case the trigger anticipates.

**Waiting for the trigger would mean waiting for a wrong cost basis.** A wrong order raises no
error. It produces a plausible number, the realised gain of a LIFO sale, and that figure is the
one a tax return would use. The first sign of a wrong caller would be a wrong figure that someone
had already relied on ([#69](https://github.com/NaimElijah/FinAssist/issues/69)).

## Decision

**`consume_fifo` sorts the lots it is given by `acquired_on`, oldest first, before it totals or
consumes them.** Its name is now true for every caller, and FIFO is a property of the domain
rather than of the one use case that remembered to sort.

**Lots acquired on the same day keep the order they arrive in.** The sort is stable, as
`DisposeUnits`' was. A date cannot say which of two same-day purchases came first, so the order
the ledger returns them in decides. That is better than a tie-break nobody chose, such as the
cheaper lot first, which would itself be a cost-basis choice.

**The lots that remain are returned oldest first**, whatever order they arrived in.

**`DisposeUnits` no longer sorts.** The use case goes back to holding no rules, which is what
ADR-021 asks of the application layer.

**Pinned by:**

- `Given_lots_newest_first_When_consumed_Then_the_oldest_is_consumed_first` and
  `Given_lots_acquired_the_same_day_When_consumed_Then_they_go_in_the_order_given`, in
  `test_portfolio.py`;
- `Given_lots_in_any_order_When_consumed_Then_the_disposal_is_the_oldest_first_one`, in
  `test_portfolio_properties.py`. Any permutation of a holding must sell exactly what its
  oldest-first order sells.

## Consequences

**Makes easy**

- **A second caller, such as #158's, gets FIFO without knowing it has to ask for it.**
- **ADR-013's sharpest edge is gone**, and the README no longer lists it as a limitation.

**Makes hard**

- **A caller that wants another order cannot get it from this function.** Specific-lot
  identification, which ADR-013 keeps available, will need a function of its own, named for what
  it does, rather than an argument that turns FIFO off.

**Accepted cost**

- **A sort on every disposal.** It runs over the lots of one instrument, which are few. The use
  case no longer pays for a second one.

## Alternatives considered

- **Keep the order the caller's, and say so in the name or the docstring.** Rejected. It is
  honest, but it leaves the FIFO guarantee in whichever caller remembers it. A domain function
  named for FIFO that can return a LIFO basis is an illegal state the type system could not stop,
  and the function itself can.
- **Refuse lots that are out of order, with a `ValueError`.** Rejected. It fails loudly, which is
  better than a wrong number. But it makes every caller sort, so the rule still lives outside the
  domain, and sorting is total: no input is out of order once the function orders it.
- **Wait for a caller to get it wrong, as ADR-013's trigger says.** Rejected, for the reason under
  Context: the first evidence would be a wrong figure that had already been used.

## Revisit when

- **Specific-lot identification is built.** It consumes the lots the owner names, in no date
  order, so it needs its own function beside this one. This decision does not stretch to cover it.
- **The ledger records a time of acquisition, or a sequence, finer than a date.** Same-day lots
  could then be ordered by what happened rather than by the order they arrive in.
