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


@st.composite
def lots(draw: st.DrawFn) -> tuple[Lot, ...]:
    """A holding of one to six lots in one currency, ordered oldest first as FIFO expects."""
    count = draw(st.integers(min_value=1, max_value=6))
    quantities = draw(
        st.lists(
            st.integers(min_value=1, max_value=500),
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
            Decimal(quantity),
            Money(Decimal(cost).scaleb(-2), Currency.USD),
            EPOCH + timedelta(days=index),
        )
        for index, (quantity, cost) in enumerate(zip(quantities, costs, strict=True))
    )


def held(holding: tuple[Lot, ...]) -> Decimal:
    return sum((lot.quantity for lot in holding), Decimal(0))


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
    sold = data.draw(st.integers(min_value=1, max_value=int(total)))

    disposal = consume_fifo(holding, Decimal(sold))

    assert held(disposal.remaining) == total - Decimal(sold)


@given(lots(), st.data())
def Given_any_disposal_When_consumed_Then_the_input_lots_are_untouched(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    before = tuple(holding)
    sold = data.draw(st.integers(min_value=1, max_value=int(held(holding))))

    consume_fifo(holding, Decimal(sold))

    assert holding == before


@given(lots(), st.data())
def Given_any_disposal_When_consumed_Then_the_oldest_lots_go_first(
    holding: tuple[Lot, ...], data: st.DataObject
) -> None:
    sold = data.draw(st.integers(min_value=1, max_value=int(held(holding))))

    remaining = consume_fifo(holding, Decimal(sold)).remaining

    # Whatever survives is a tail of the original: FIFO consumes from the front, so no lot
    # older than a surviving one can still be there.
    assert [lot.acquired_on for lot in remaining] == sorted(lot.acquired_on for lot in remaining)
    if remaining:
        consumed_dates = [
            lot.acquired_on for lot in holding if lot.acquired_on < remaining[0].acquired_on
        ]
        assert all(date_ < remaining[0].acquired_on for date_ in consumed_dates)


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
