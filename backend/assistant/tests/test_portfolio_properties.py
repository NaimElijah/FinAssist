"""Conservation laws for the FIFO cost basis (ADR-013).

Disposal is the one place in the assistant where state is carried across a computation - lots
in, a smaller set of lots plus a cost out - so it is where an off-by-one silently produces a
plausible number. These assert what must hold of every disposal rather than of the handful the
example suite walks through.

Determinism comes from ``tests/conftest.py``.
"""

from datetime import date, timedelta
from decimal import Decimal

import pytest
from hypothesis import given
from hypothesis import strategies as st

from finassist_assistant.portfolio.domain.analysis import (
    InsufficientHoldingError,
    consume_fifo,
    position,
)
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

VWCE = Symbol("VWCE")
EPOCH = date(2026, 1, 1)

QUANTITY_PLACES = 4
"""The finest quantity a generated lot holds: fractional units, as a fund share often is. Every
generated quantity, and every quantity sold, is a whole number of these."""

_QUANTUM = Decimal(1).scaleb(-QUANTITY_PLACES)


@st.composite
def lots(draw: st.DrawFn) -> tuple[Lot, ...]:
    """A holding of one to six lots in one currency, each acquired on its own day, oldest first.

    The currency is drawn per holding - one holding is one currency - and each unit cost is
    exact at that currency's minor units, so a JPY holding costs whole yen. Quantities are whole
    or fractional, down to :data:`QUANTITY_PLACES` places.
    """
    currency = draw(st.sampled_from(list(Currency)))
    count = draw(st.integers(min_value=1, max_value=6))
    quantities = draw(
        st.lists(
            st.builds(
                lambda units, places: Decimal(units).scaleb(-places),
                st.integers(min_value=1, max_value=500 * 10**QUANTITY_PLACES),
                st.integers(min_value=0, max_value=QUANTITY_PLACES),
            ),
            min_size=count,
            max_size=count,
        )
    )
    costs = draw(
        st.lists(st.integers(min_value=1, max_value=100_000), min_size=count, max_size=count)
    )
    return tuple(
        Lot(
            VWCE,
            quantity,
            Money(Decimal(cost).scaleb(-currency.minor_units), currency),
            EPOCH + timedelta(days=index),
        )
        for index, (quantity, cost) in enumerate(zip(quantities, costs, strict=True))
    )


def held(holding: tuple[Lot, ...]) -> Decimal:
    return sum((lot.quantity for lot in holding), Decimal(0))


def a_sale_of(holding: tuple[Lot, ...], data: st.DataObject) -> Decimal:
    """Draws a quantity to sell: at least one quantum, and no more than the holding holds."""
    units_held = int(held(holding) / _QUANTUM)
    return Decimal(data.draw(st.integers(min_value=1, max_value=units_held))) * _QUANTUM


@given(lots())
def Given_any_holding_When_a_position_is_taken_Then_quantity_is_the_sum_of_its_lots(
    holding: tuple[Lot, ...],
) -> None:
    assert position(holding).quantity == held(holding)


@given(lots())
def Given_any_holding_When_a_position_is_taken_Then_cost_is_the_sum_of_its_lots(
    holding: tuple[Lot, ...],
) -> None:
    total = holding[0].cost
    for lot in holding[1:]:
        total = total.plus(lot.cost)

    assert position(holding).cost == total


@given(lots(), st.data())
def Given_any_disposal_When_consumed_Then_the_remainder_is_what_was_not_sold(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    total = held(holding)
    sold = a_sale_of(holding, data)

    disposal = consume_fifo(holding, sold)

    assert held(disposal.remaining) == total - sold


@given(lots(), st.data())
def Given_any_disposal_When_consumed_Then_the_input_lots_are_untouched(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    before = tuple(holding)
    sold = a_sale_of(holding, data)

    consume_fifo(holding, sold)

    assert holding == before


@given(lots(), st.data())
def Given_any_disposal_When_consumed_Then_the_oldest_lots_go_first(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    sold = a_sale_of(holding, data)

    remaining = consume_fifo(holding, sold).remaining

    # Whatever survives is a *suffix* of the input: FIFO consumes from the front, so the
    # remaining acquisition dates must be the last N of the original, in order.
    #
    # The previous version filtered the input by `date < remaining[0].date` and then
    # asserted that same predicate over the result - true for every input by construction,
    # so the property named here could not fail.
    dates = [lot.acquired_on for lot in holding]
    assert [lot.acquired_on for lot in remaining] == dates[len(dates) - len(remaining) :]


@given(lots(), st.data())
def Given_lots_in_any_order_When_consumed_Then_the_disposal_is_the_oldest_first_one(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    # Every lot has a date of its own, so oldest first is one order only, and whichever order
    # the lots arrive in must sell exactly what that order sells (#69).
    shuffled = tuple(data.draw(st.permutations(holding)))
    sold = a_sale_of(holding, data)

    assert consume_fifo(shuffled, sold) == consume_fifo(holding, sold)


@given(lots(), st.data())
def Given_a_disposal_larger_than_the_holding_When_consumed_Then_it_is_refused(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    excess = data.draw(st.integers(min_value=1, max_value=1000))

    with pytest.raises(InsufficientHoldingError):
        consume_fifo(holding, held(holding) + Decimal(excess))


@given(lots())
def Given_a_disposal_of_everything_When_consumed_Then_nothing_remains_and_the_cost_is_the_whole(
    holding: tuple[Lot, ...],
) -> None:
    disposal = consume_fifo(holding, held(holding))

    assert disposal.remaining == ()
    assert disposal.cost_of_sale == position(holding).cost
