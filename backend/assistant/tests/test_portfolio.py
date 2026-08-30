"""FIFO cost basis and the figures derived from it (ADR-013)."""

from datetime import date
from decimal import Decimal

import pytest

from finassist_assistant.portfolio.domain.analysis import (
    Disposal,
    InsufficientHoldingError,
    consume_fifo,
    market_value,
    position,
    realised_gain,
    unrealised_gain,
)
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, CurrencyMismatchError, Money

VWCE = Symbol("VWCE")


def usd(amount: str) -> Money:
    return Money.of(amount, Currency.USD)


def lot(quantity: str, unit_cost: str, day: int) -> Lot:
    return Lot(VWCE, Decimal(quantity), usd(unit_cost), date(2026, 1, day))


def Given_symbols_differing_in_case_When_compared_Then_they_are_one_symbol() -> None:
    assert Symbol("vwce") == Symbol("  VWCE ")


def Given_a_blank_symbol_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must not be blank"):
        Symbol("   ")


def Given_a_lot_of_zero_or_fewer_units_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="positive quantity"):
        Lot(VWCE, Decimal(0), usd("100.00"), date(2026, 1, 1))


def Given_several_lots_When_a_position_is_taken_Then_quantity_and_cost_are_summed() -> None:
    held = position((lot("10", "100.00", 1), lot("5", "120.00", 2)))
    assert held.quantity == Decimal(15)
    assert held.cost == usd("1600.00")


def Given_no_lots_When_a_position_is_taken_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="at least one lot"):
        position(())


def Given_lots_of_different_symbols_When_a_position_is_taken_Then_it_is_refused() -> None:
    other = Lot(Symbol("IWDA"), Decimal(1), usd("50.00"), date(2026, 1, 1))
    with pytest.raises(ValueError, match="more than one symbol"):
        position((lot("10", "100.00", 1), other))


def Given_a_sale_of_a_whole_lot_When_consumed_Then_that_lot_is_gone_at_its_own_cost() -> None:
    disposal = consume_fifo((lot("10", "100.00", 1), lot("5", "120.00", 2)), Decimal(10))
    assert disposal.cost_of_sale == usd("1000.00")
    assert len(disposal.remaining) == 1
    assert disposal.remaining[0].quantity == Decimal(5)


def Given_a_sale_spanning_two_lots_When_consumed_Then_the_oldest_goes_first() -> None:
    disposal = consume_fifo((lot("10", "100.00", 1), lot("5", "120.00", 2)), Decimal(12))
    # 10 units at 100 plus 2 at 120 - not 12 at any average.
    assert disposal.cost_of_sale == usd("1240.00")
    assert len(disposal.remaining) == 1
    assert disposal.remaining[0].quantity == Decimal(3)
    assert disposal.remaining[0].unit_cost == usd("120.00")


def Given_a_partly_consumed_lot_When_consumed_Then_the_remainder_keeps_its_date_and_cost() -> None:
    disposal = consume_fifo((lot("10", "100.00", 1),), Decimal(4))
    remainder = disposal.remaining[0]
    assert remainder.quantity == Decimal(6)
    assert remainder.unit_cost == usd("100.00")
    assert remainder.acquired_on == date(2026, 1, 1)


def Given_a_sale_of_everything_When_consumed_Then_nothing_remains() -> None:
    disposal = consume_fifo((lot("10", "100.00", 1),), Decimal(10))
    assert disposal == Disposal((), usd("1000.00"))


def Given_a_sale_larger_than_the_holding_When_consumed_Then_it_is_refused() -> None:
    with pytest.raises(InsufficientHoldingError, match="only 10 are held"):
        consume_fifo((lot("10", "100.00", 1),), Decimal(11))


def Given_a_sale_of_zero_When_consumed_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="positive quantity"):
        consume_fifo((lot("10", "100.00", 1),), Decimal(0))


def Given_proceeds_above_cost_When_realised_gain_is_taken_Then_it_is_the_difference() -> None:
    assert realised_gain(usd("1300.00"), usd("1000.00")) == usd("300.00")


def Given_proceeds_below_cost_When_realised_gain_is_taken_Then_it_is_a_loss() -> None:
    assert realised_gain(usd("900.00"), usd("1000.00")) == usd("-100.00")


def Given_a_position_and_a_price_When_valued_Then_gain_is_value_less_cost() -> None:
    held = position((lot("10", "100.00", 1),))
    assert market_value(held, usd("130.00")) == usd("1300.00")
    assert unrealised_gain(held, usd("130.00")) == usd("300.00")
    assert unrealised_gain(held, usd("90.00")) == usd("-100.00")


def Given_a_fractional_quantity_When_cost_is_taken_Then_it_rounds_instead_of_throwing() -> None:
    # Fractional shares are ordinary, and 1.5 at 12.33 is 18.495 - which no two-decimal currency
    # can hold. Lot.cost used exact multiplication and raised on every fractional holding.
    fractional = Lot(VWCE, Decimal("1.5"), usd("12.33"), date(2026, 1, 1))
    assert fractional.cost == usd("18.50")


def Given_fractional_lots_When_valued_and_disposed_Then_every_figure_is_computable() -> None:
    lots = (Lot(VWCE, Decimal("1.5"), usd("12.33"), date(2026, 1, 1)),)
    held = position(lots)
    assert held.quantity == Decimal("1.5")
    assert market_value(held, usd("20.00")) == usd("30.00")
    assert unrealised_gain(held, usd("20.00")) == usd("11.50")
    assert consume_fifo(lots, Decimal("0.5")).cost_of_sale == usd("6.17")


def Given_lots_When_a_sale_is_consumed_Then_the_originals_are_unchanged() -> None:
    lots = (lot("10", "100.00", 1),)
    consume_fifo(lots, Decimal(4))
    assert lots[0].quantity == Decimal(10)


def ils(amount: str) -> Money:
    return Money.of(amount, Currency.ILS)


def Given_a_lot_costing_a_negative_amount_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="negative"):
        Lot(VWCE, Decimal("1"), usd("-10.00"), date(2026, 1, 1))


def Given_a_lot_When_its_quantity_is_replaced_with_zero_Then_it_is_refused() -> None:
    # with_quantity goes through Lot.__init__, so it cannot be used to sidestep the invariant.
    with pytest.raises(ValueError, match="positive quantity"):
        lot("2", "10.00", 1).with_quantity(Decimal("0"))


def Given_lots_in_two_currencies_When_a_position_is_taken_Then_it_is_refused() -> None:
    mixed = (
        Lot(VWCE, Decimal("1"), usd("10.00"), date(2026, 1, 1)),
        Lot(VWCE, Decimal("1"), ils("10.00"), date(2026, 1, 2)),
    )

    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        position(mixed)


def Given_lots_in_two_currencies_When_consumed_Then_it_is_refused() -> None:
    mixed = (
        Lot(VWCE, Decimal("1"), usd("10.00"), date(2026, 1, 1)),
        Lot(VWCE, Decimal("1"), ils("10.00"), date(2026, 1, 2)),
    )

    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        consume_fifo(mixed, Decimal("2"))


def Given_proceeds_in_another_currency_When_a_gain_is_realised_Then_it_is_refused() -> None:
    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        realised_gain(ils("100.00"), usd("80.00"))


def Given_a_price_in_another_currency_When_an_unrealised_gain_is_taken_Then_it_is_refused() -> None:
    held = position((lot("2", "10.00", 1),))

    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        unrealised_gain(held, ils("12.00"))


def Given_a_foreign_price_When_a_position_is_valued_Then_it_answers_in_that_currency() -> None:
    # Recorded, not endorsed. market_value is price times quantity and never combines two
    # amounts, so ADR-012's guard has nothing to fire on: valuing a USD holding at an ILS price
    # yields an ILS figure with no complaint, even though the Position carries its own currency
    # and could be checked against. unrealised_gain, which does combine, refuses the same pair.
    # Left as it stands because tightening it is a domain decision, not a testing one:
    # https://github.com/NaimElijah/FinAssist/issues/17 (@NaimElijah).
    held = position((lot("2", "10.00", 1),))

    assert market_value(held, ils("12.00")) == ils("24.00")
    assert held.cost.currency is Currency.USD


def Given_no_lots_at_all_When_consumed_Then_it_is_refused_as_an_insufficient_holding() -> None:
    with pytest.raises(InsufficientHoldingError):
        consume_fifo((), Decimal("1"))


def Given_a_sale_of_a_negative_quantity_When_consumed_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="positive"):
        consume_fifo((lot("2", "10.00", 1),), Decimal("-1"))
