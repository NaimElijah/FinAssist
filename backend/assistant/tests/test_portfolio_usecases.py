"""The portfolio use cases: reading a position, valuing it, and selling out of it.

The case worth reading twice is the FIFO one. The ledger may return lots in any order, and a
newest-first sale yields a LIFO cost basis, in silence, with a plausible number and no error.
ADR-013 chose FIFO, and ``consume_fifo`` makes it true by sorting the lots itself (ADR-041). The
test below hands ``DisposeUnits`` lots in the wrong order deliberately, so the guarantee is held
at the use case's boundary as well as the domain function's.
"""

from datetime import date
from decimal import Decimal

import pytest

from finassist_assistant.portfolio.application.port import (
    HoldingNotFoundError,
    PriceUnavailableError,
)
from finassist_assistant.portfolio.application.usecase import (
    DisposeUnits,
    ValueHolding,
    ViewPosition,
)
from finassist_assistant.portfolio.domain.analysis import InsufficientHoldingError
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, CurrencyMismatchError, Money

VOO = Symbol("VOO")

CHEAP_AND_OLD = Lot(VOO, Decimal("10"), Money.of("100.00", Currency.USD), date(2026, 1, 10))
DEAR_AND_NEW = Lot(VOO, Decimal("10"), Money.of("200.00", Currency.USD), date(2026, 6, 10))


class FakeLedger:
    """Answers with whatever it was given, in the order it was given it."""

    def __init__(self, *lots: Lot) -> None:
        self.lots = lots

    def lots_for(self, symbol: Symbol) -> tuple[Lot, ...]:
        return tuple(lot for lot in self.lots if lot.symbol == symbol)


class FixedPrice:
    def __init__(self, price: Money) -> None:
        self.price = price

    def unit_price(self, symbol: Symbol) -> Money:
        return self.price


class NoPrice:
    def unit_price(self, symbol: Symbol) -> Money:
        raise PriceUnavailableError(symbol)


def Given_lots_of_one_symbol_When_the_position_is_read_Then_quantity_and_cost_are_summed() -> None:
    held = ViewPosition(FakeLedger(CHEAP_AND_OLD, DEAR_AND_NEW)).of(VOO)

    assert held.quantity == Decimal("20")
    assert held.cost == Money.of("3000.00", Currency.USD)


def Given_nothing_held_When_the_position_is_read_Then_it_is_refused() -> None:
    with pytest.raises(HoldingNotFoundError, match="No lots are held"):
        ViewPosition(FakeLedger()).of(VOO)


def Given_a_holding_and_a_price_When_valued_Then_the_market_value_and_gain_are_reported() -> None:
    valuation = ValueHolding(
        FakeLedger(CHEAP_AND_OLD, DEAR_AND_NEW), FixedPrice(Money.of("250.00", Currency.USD))
    ).of(VOO)

    assert valuation.market_value == Money.of("5000.00", Currency.USD)
    assert valuation.unrealised_gain == Money.of("2000.00", Currency.USD)
    assert valuation.unit_price == Money.of("250.00", Currency.USD)


def Given_a_price_below_cost_When_valued_Then_the_gain_is_negative() -> None:
    valuation = ValueHolding(
        FakeLedger(DEAR_AND_NEW), FixedPrice(Money.of("150.00", Currency.USD))
    ).of(VOO)

    assert valuation.unrealised_gain == Money.of("-500.00", Currency.USD)


def Given_no_usable_price_When_valued_Then_it_is_refused() -> None:
    with pytest.raises(PriceUnavailableError, match="No usable price"):
        ValueHolding(FakeLedger(CHEAP_AND_OLD), NoPrice()).of(VOO)


def Given_nothing_held_When_valued_Then_the_price_is_never_asked_for() -> None:
    # Ordering matters: asking a feed for a price before knowing anything is held would cost a
    # network call to value nothing.
    with pytest.raises(HoldingNotFoundError):
        ValueHolding(FakeLedger(), NoPrice()).of(VOO)


def Given_a_price_in_another_currency_When_valued_Then_it_is_refused() -> None:
    with pytest.raises(CurrencyMismatchError):
        ValueHolding(FakeLedger(CHEAP_AND_OLD), FixedPrice(Money.of("250.00", Currency.ILS))).of(
            VOO
        )


def Given_lots_out_of_order_When_units_are_disposed_of_Then_the_oldest_is_consumed_first() -> None:
    # Deliberately newest-first. Consumed in that order this yields a cost of sale of 2000.00 -
    # a LIFO basis, silently, with no error and a number that looks right.
    disposition = DisposeUnits(FakeLedger(DEAR_AND_NEW, CHEAP_AND_OLD)).of(
        VOO, Decimal("10"), Money.of("2500.00", Currency.USD)
    )

    assert disposition.disposal.cost_of_sale == Money.of("1000.00", Currency.USD)
    assert disposition.realised_gain == Money.of("1500.00", Currency.USD)


def Given_a_partial_lot_When_units_are_disposed_of_Then_the_rest_of_it_remains() -> None:
    disposition = DisposeUnits(FakeLedger(CHEAP_AND_OLD, DEAR_AND_NEW)).of(
        VOO, Decimal("4"), Money.of("1000.00", Currency.USD)
    )

    assert disposition.disposal.cost_of_sale == Money.of("400.00", Currency.USD)
    assert [lot.quantity for lot in disposition.remaining] == [Decimal("6"), Decimal("10")]


def Given_more_units_than_are_held_When_disposed_of_Then_it_is_refused() -> None:
    with pytest.raises(InsufficientHoldingError, match="only 10 are held"):
        DisposeUnits(FakeLedger(CHEAP_AND_OLD)).of(
            VOO, Decimal("11"), Money.of("2500.00", Currency.USD)
        )


def Given_a_quantity_of_zero_When_disposed_of_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="positive quantity"):
        DisposeUnits(FakeLedger(CHEAP_AND_OLD)).of(
            VOO, Decimal("0"), Money.of("0.00", Currency.USD)
        )


def Given_nothing_held_When_disposed_of_Then_it_is_refused_before_the_domain_sees_it() -> None:
    with pytest.raises(HoldingNotFoundError):
        DisposeUnits(FakeLedger()).of(VOO, Decimal("1"), Money.of("10.00", Currency.USD))


def Given_lots_acquired_on_one_day_When_ordered_Then_the_ledger_order_is_kept() -> None:
    # A stable sort, so a same-day tie is not reshuffled by a tie-break nobody chose.
    first = Lot(VOO, Decimal("1"), Money.of("100.00", Currency.USD), date(2026, 1, 10))
    second = Lot(VOO, Decimal("1"), Money.of("300.00", Currency.USD), date(2026, 1, 10))

    disposition = DisposeUnits(FakeLedger(first, second)).of(
        VOO, Decimal("1"), Money.of("500.00", Currency.USD)
    )

    assert disposition.disposal.cost_of_sale == Money.of("100.00", Currency.USD)
