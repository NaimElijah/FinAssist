"""Frozen values the portfolio context operates on: Symbol, Lot, Position.

This context's own vocabulary. A ledger DTO or a market-data quote becomes one of these types
at the anti-corruption layer in ``adapters/outbound`` and never arrives here in its foreign
shape.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from finassist_assistant.shared import Money


@dataclass(frozen=True, slots=True)
class Symbol:
    """An instrument's ticker, normalised so that ``vwce`` and ``VWCE`` are one symbol."""

    value: str

    def __post_init__(self) -> None:
        stripped = self.value.strip().upper()
        if not stripped:
            raise ValueError("A symbol must not be blank")
        object.__setattr__(self, "value", stripped)

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class Lot:
    """One purchase: a quantity acquired on a date at a unit cost.

    A lot is never mutated. Selling produces a new list of lots, which is what keeps the cost
    basis auditable — you can point at exactly which purchases a sale consumed (ADR-013).
    """

    symbol: Symbol
    quantity: Decimal
    unit_cost: Money
    acquired_on: date

    def __post_init__(self) -> None:
        if self.quantity <= 0:
            raise ValueError(f"A lot must hold a positive quantity but held {self.quantity}")
        if self.unit_cost.is_negative:
            raise ValueError(f"A lot cannot cost a negative amount but cost {self.unit_cost}")

    @property
    def cost(self) -> Money:
        """What this lot cost in total."""
        return self.unit_cost.times(self.quantity)

    def with_quantity(self, quantity: Decimal) -> Lot:
        """The same purchase, part of it. Used when a sale consumes a lot only partly."""
        return Lot(self.symbol, quantity, self.unit_cost, self.acquired_on)


@dataclass(frozen=True, slots=True)
class Position:
    """What is held in one instrument, and what it cost.

    Derived from lots by :func:`finassist_assistant.portfolio.domain.analysis.position`; never
    assembled by hand, so it cannot disagree with the lots behind it.
    """

    symbol: Symbol
    quantity: Decimal
    cost: Money
