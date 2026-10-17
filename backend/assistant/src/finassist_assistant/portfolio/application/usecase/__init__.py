"""Orchestration only: wire ports to domain functions, hold no rules.

Each use case is a frozen dataclass holding its ports, with one named method. That is the
constructor injection the ledger's services use, spelled the way Python spells it, and it is
what lets every case below be tested by constructing it with fakes (ADR-021).
"""

from dataclasses import dataclass
from decimal import Decimal

from finassist_assistant.portfolio.application.port import (
    HoldingNotFoundError,
    LedgerPort,
    QuotePort,
)
from finassist_assistant.portfolio.domain.analysis import (
    Disposal,
    consume_fifo,
    market_value,
    position,
    realised_gain,
    unrealised_gain,
)
from finassist_assistant.portfolio.domain.model import Lot, Position, Symbol
from finassist_assistant.shared import Money

__all__ = [
    "DisposeUnits",
    "Disposition",
    "Valuation",
    "ValueHolding",
    "ViewPosition",
]


@dataclass(frozen=True, slots=True)
class Valuation:
    """What a holding is worth at a given unit price, and what that implies about gain."""

    position: Position
    unit_price: Money
    market_value: Money
    unrealised_gain: Money


@dataclass(frozen=True, slots=True)
class Disposition:
    """The result of selling units: what is left, what those units cost, and the gain."""

    disposal: Disposal
    proceeds: Money
    realised_gain: Money

    @property
    def remaining(self) -> tuple[Lot, ...]:
        """The lots still held afterwards, oldest first."""
        return self.disposal.remaining


def _held(ledger: LedgerPort, symbol: Symbol) -> tuple[Lot, ...]:
    lots = ledger.lots_for(symbol)
    if not lots:
        raise HoldingNotFoundError(symbol)
    return lots


@dataclass(frozen=True, slots=True)
class ViewPosition:
    """Reads total quantity and total cost across the lots of one instrument."""

    ledger: LedgerPort

    def of(self, symbol: Symbol) -> Position:
        """:raises HoldingNotFoundError: if nothing is held for the symbol"""
        return position(_held(self.ledger, symbol))


@dataclass(frozen=True, slots=True)
class ValueHolding:
    """Values a holding at the current price, and says what is unrealised."""

    ledger: LedgerPort
    quotes: QuotePort

    def of(self, symbol: Symbol) -> Valuation:
        """
        :raises HoldingNotFoundError: if nothing is held for the symbol
        :raises PriceUnavailableError: if no usable price exists
        :raises CurrencyMismatchError: if the price is not in the currency the lots cost
        """
        held = position(_held(self.ledger, symbol))
        price = self.quotes.unit_price(symbol)
        return Valuation(
            position=held,
            unit_price=price,
            market_value=market_value(held, price),
            unrealised_gain=unrealised_gain(held, price),
        )


@dataclass(frozen=True, slots=True)
class DisposeUnits:
    """Sells units oldest lot first, and says what they cost and what was gained."""

    ledger: LedgerPort

    def of(self, symbol: Symbol, quantity: Decimal, proceeds: Money) -> Disposition:
        """
        :raises HoldingNotFoundError: if nothing is held for the symbol
        :raises InsufficientHoldingError: if fewer units are held than requested
        :raises ValueError: if the quantity is not positive
        :raises CurrencyMismatchError: if the lots cost more than one currency, or the proceeds
            are in a currency other than theirs
        """
        disposal = consume_fifo(_held(self.ledger, symbol), quantity)
        gain = realised_gain(proceeds, disposal.cost_of_sale)
        return Disposition(disposal=disposal, proceeds=proceeds, realised_gain=gain)
