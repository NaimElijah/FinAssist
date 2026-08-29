"""Total, deterministic functions over lots. No I/O, no clock, no framework.

Every function here is a pure transformation: given the same lots it returns the same answer,
and it never reads anything it was not handed. That is what lets the analysis be tested by
calling it, and it is why this context carries no aggregates (ADR-007).
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from finassist_assistant.portfolio.domain.model import Lot, Position, Symbol
from finassist_assistant.shared import Money


class InsufficientHoldingError(ValueError):
    """Raised when a sale would consume more units than are held.

    Selling what you do not hold is not a small numeric error to absorb; it means the lots and
    reality have diverged, and continuing would produce a confident, wrong gain figure.
    """

    def __init__(self, held: Decimal, requested: Decimal) -> None:
        super().__init__(f"Cannot dispose of {requested} units; only {held} are held")


@dataclass(frozen=True, slots=True)
class Disposal:
    """The outcome of a sale: what is left, and what the sold units originally cost."""

    remaining: tuple[Lot, ...]
    cost_of_sale: Money


def position(lots: tuple[Lot, ...]) -> Position:
    """Totals a set of lots for one instrument.

    :raises ValueError: if the lots are empty or cover more than one symbol
    """
    if not lots:
        raise ValueError("A position needs at least one lot")

    symbols = {lot.symbol for lot in lots}
    if len(symbols) > 1:
        raise ValueError(f"Lots cover more than one symbol: {sorted(str(s) for s in symbols)}")

    total_quantity = sum((lot.quantity for lot in lots), Decimal(0))
    total_cost = lots[0].cost
    for lot in lots[1:]:
        total_cost = total_cost.plus(lot.cost)
    return Position(lots[0].symbol, total_quantity, total_cost)


def consume_fifo(lots: tuple[Lot, ...], quantity: Decimal) -> Disposal:
    """Sells ``quantity`` units, taking the oldest lots first (ADR-013).

    Lots are consumed in the order given; the caller sorts by ``acquired_on`` if that order is
    not already the acquisition order. A lot consumed only partly is split, so the remainder
    keeps its original unit cost and acquisition date.

    :raises InsufficientHoldingError: if fewer units are held than requested
    """
    if quantity <= 0:
        raise ValueError(f"A disposal must be of a positive quantity but was {quantity}")

    held = sum((lot.quantity for lot in lots), Decimal(0))
    if quantity > held:
        raise InsufficientHoldingError(held, quantity)

    outstanding = quantity
    cost_of_sale = Money.zero(lots[0].unit_cost.currency)
    remaining: list[Lot] = []

    for lot in lots:
        if outstanding <= 0:
            remaining.append(lot)
        elif lot.quantity <= outstanding:
            cost_of_sale = cost_of_sale.plus(lot.cost)
            outstanding -= lot.quantity
        else:
            cost_of_sale = cost_of_sale.plus(lot.unit_cost.times(outstanding))
            remaining.append(lot.with_quantity(lot.quantity - outstanding))
            outstanding = Decimal(0)

    return Disposal(tuple(remaining), cost_of_sale)


def realised_gain(proceeds: Money, cost_of_sale: Money) -> Money:
    """What a sale actually made, once what the units cost is taken off."""
    return proceeds.minus(cost_of_sale)


def unrealised_gain(held: Position, unit_price: Money) -> Money:
    """What the position would make if sold at ``unit_price``.

    Takes a price as :class:`Money`, not a market-data quote. A quote belongs to the
    ``marketdata`` context, and importing one here would couple two contexts — which the
    architecture tests fail the build for. Translating a quote into a price is the caller's job.
    """
    return unit_price.times(held.quantity).minus(held.cost)


def market_value(held: Position, unit_price: Money) -> Money:
    """What the position is worth at ``unit_price``."""
    return unit_price.times(held.quantity)


__all__ = [
    "Disposal",
    "InsufficientHoldingError",
    "Symbol",
    "consume_fifo",
    "market_value",
    "position",
    "realised_gain",
    "unrealised_gain",
]
