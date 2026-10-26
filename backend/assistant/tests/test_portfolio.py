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


@pytest.mark.parametrize("quantity", ["0", "-1", "-0.0001"], ids=["zero", "negative", "fraction"])
def Given_a_lot_of_zero_or_fewer_units_When_built_Then_it_is_refused(quantity: str) -> None:
    with pytest.raises(ValueError, match="positive quantity"):
        Lot(VWCE, Decimal(quantity), usd("100.00"), date(2026, 1, 1))


def Given_several_lots_When_a_position_is_taken_Then_quantity_and_cost_are_summed() -> None:
    held = position((lot("10", "100.00", 1), lot("5", "120.00", 2)))
    assert held.quantity == Decimal(15)
    assert held.cost == usd("1600.00")


def Given_lots_past_28_digits_When_a_position_is_taken_Then_the_quantity_is_exact() -> None:
    """The quantities were totalled with ``sum`` in Python's default context, which keeps 28
    significant digits, so these two lots came back as exactly ten units and the second one
    vanished from the position. Java's ``BigDecimal`` never rounds a sum (#70)."""
    held = position(
        (lot("10", "100.00", 1), lot("0.00000000000000000000000000000001", "100.00", 2))
    )

    assert held.quantity == Decimal("10.00000000000000000000000000000001")


def Given_lot_costs_past_28_digits_When_a_position_is_taken_Then_the_cost_is_exact() -> None:
    """The cost is totalled with ``Money.plus``, which rounded past 28 significant digits too
    (#70)."""
    held = position((lot("1", "1000000000000000000000000000000.00", 1), lot("1", "0.01", 2)))

    assert str(held.cost) == "1000000000000000000000000000000.01 USD"


def Given_lots_at_a_tiny_exponent_When_a_position_is_taken_Then_the_quantity_is_exact() -> None:
    """Below Python's default exponent range, about 1E-1000000, an exact sum is still exact. A
    context that takes that range from the default rounds there, and with rounding trapped the
    total raised ``decimal.Inexact`` instead (#70)."""
    held = position((lot("1E-1000005", "100.00", 1), lot("2E-1000005", "100.00", 2)))

    assert held.quantity == Decimal("3E-1000005")


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


def Given_lots_newest_first_When_consumed_Then_the_oldest_is_consumed_first() -> None:
    """Consumed in the order given, these cost 1100.00: a LIFO basis, with no error (#69)."""
    disposal = consume_fifo((lot("5", "120.00", 2), lot("10", "100.00", 1)), Decimal(10))

    assert disposal.cost_of_sale == usd("1000.00")
    assert disposal.remaining == (lot("5", "120.00", 2),)


def Given_lots_acquired_the_same_day_When_consumed_Then_they_go_in_the_order_given() -> None:
    """A date says nothing about which of two same-day purchases came first, so the order the
    lots arrive in decides, rather than a tie-break nobody chose, such as the cheaper first.

    Both orders are sold, because a tie-break read off the lots, cheaper or dearer first, picks
    the same lot whichever order they arrive in, and so fails one of the two."""
    dear, cheap = lot("3", "200.00", 1), lot("3", "100.00", 1)

    assert consume_fifo((dear, cheap), Decimal(3)) == Disposal((cheap,), usd("600.00"))
    assert consume_fifo((cheap, dear), Decimal(3)) == Disposal((dear,), usd("300.00"))


def Given_a_partly_consumed_lot_When_consumed_Then_the_remainder_keeps_its_date_and_cost() -> None:
    disposal = consume_fifo((lot("10", "100.00", 1),), Decimal(4))
    remainder = disposal.remaining[0]
    assert remainder.quantity == Decimal(6)
    assert remainder.unit_cost == usd("100.00")
    assert remainder.acquired_on == date(2026, 1, 1)


def Given_a_sale_finer_than_28_digits_When_consumed_Then_the_remainder_is_exact() -> None:
    """Python's default context keeps 28 significant digits and rounds past that silently, so the
    remainder of ``10 - 1E-32`` came back as ten: a sale that consumed nothing. Java's
    ``BigDecimal`` never rounds a subtraction."""
    disposal = consume_fifo((lot("10", "100.00", 1),), Decimal("1E-32"))

    assert disposal.remaining[0].quantity == Decimal("9.99999999999999999999999999999999")


def Given_a_sale_at_a_tiny_exponent_When_consumed_Then_the_remainder_is_exact() -> None:
    """The context ``consume_fifo`` totals in took its exponent range from Python's default, so a
    quantity below about 1E-1000000 raised ``decimal.Inexact`` instead of selling (#70)."""
    disposal = consume_fifo((lot("3E-1000005", "100.00", 1),), Decimal("1E-1000005"))

    assert disposal.remaining[0].quantity == Decimal("2E-1000005")


def Given_lots_totalling_more_than_28_digits_When_all_are_sold_Then_the_sale_is_allowed() -> None:
    """The total held is compared exactly. Rounded to 28 digits it loses its last unit, and a sale
    of everything held is refused as more than is held."""
    lots = (lot("10", "100.00", 1), lot("0.00000000000000000000000000000001", "100.00", 2))

    disposal = consume_fifo(lots, Decimal("10.00000000000000000000000000000001"))

    assert disposal.remaining == ()


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


@pytest.mark.parametrize("quantity", ["1", "2", "3"])
def Given_lots_in_two_currencies_When_any_quantity_is_consumed_Then_it_is_refused(
    quantity: str,
) -> None:
    """A sale of one unit stayed inside the oldest lot's currency and answered 10.00 USD; only a
    sale reaching the ILS lot raised. The holding is what is wrong, so whether it is refused
    must not depend on how much is sold (#244)."""
    mixed = (
        Lot(VWCE, Decimal("1"), ils("10.00"), date(2026, 1, 2)),
        Lot(VWCE, Decimal("1"), usd("10.00"), date(2026, 1, 1)),
    )

    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        consume_fifo(mixed, Decimal(quantity))


@pytest.mark.parametrize("quantity", ["1", "2", "3"])
def Given_lots_of_different_symbols_When_consumed_Then_it_is_refused(quantity: str) -> None:
    """Two instruments were consumed as one: selling both units cost 20.00 USD across VWCE and
    IWDA, with no error, though ``position()`` refuses the same lots (#244)."""
    mixed = (
        Lot(VWCE, Decimal("1"), usd("10.00"), date(2026, 1, 1)),
        Lot(Symbol("IWDA"), Decimal("1"), usd("10.00"), date(2026, 1, 2)),
    )

    with pytest.raises(ValueError, match="more than one symbol"):
        consume_fifo(mixed, Decimal(quantity))


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
