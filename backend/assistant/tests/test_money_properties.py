"""The algebraic laws Money obeys, over generated amounts rather than chosen ones.

The counterpart of ``MoneyPropertiesTest.java``, which asserts the same laws on the Java
kernel using a seeded generator. ADR-010 keeps the two implementations honest through
equivalent tests rather than shared code, and laws are the part of that contract least likely
to be stated identically by accident.

Amounts are generated as integer *minor units* and then scaled, never as arbitrary decimals:
generating decimals directly would have ``Money``'s own constructor reject most of them for
excess precision, and a property test whose inputs are mostly refused is a constructor test in
disguise.

Determinism comes from ``tests/conftest.py``.
"""

from decimal import Decimal

from hypothesis import given
from hypothesis import strategies as st

from finassist_assistant.shared import Currency, Money

MINOR_UNITS = st.integers(min_value=-(10**12), max_value=10**12)
CURRENCIES = st.sampled_from(list(Currency))


@st.composite
def money(draw: st.DrawFn, currency: Currency | None = None) -> Money:
    chosen = currency if currency is not None else draw(CURRENCIES)
    units = draw(MINOR_UNITS)
    return Money(Decimal(units).scaleb(-chosen.minor_units), chosen)


@st.composite
def pair(draw: st.DrawFn) -> tuple[Money, Money]:
    """Two amounts in one currency, since anything else is refused by design (ADR-012)."""
    currency = draw(CURRENCIES)
    return draw(money(currency)), draw(money(currency))


@given(money())
def Given_any_amount_When_constructed_Then_its_scale_is_the_currency_minor_units(
    amount: Money,
) -> None:
    # Compared without negating the exponent: Decimal.as_tuple().exponent is typed as
    # int | Literal['n', 'N', 'F'], the last three standing for NaN and Infinity, which
    # Money's constructor has already refused.
    assert amount.amount.as_tuple().exponent == -amount.currency.minor_units


@given(money())
def Given_any_amount_When_negated_twice_Then_the_original_returns(amount: Money) -> None:
    assert amount.negate().negate() == amount


@given(money())
def Given_any_amount_When_zero_is_added_Then_it_is_unchanged(amount: Money) -> None:
    assert amount.plus(Money.zero(amount.currency)) == amount


@given(money())
def Given_any_amount_When_subtracted_from_itself_Then_the_result_is_zero(amount: Money) -> None:
    assert amount.minus(amount).is_zero


@given(money())
def Given_any_amount_When_multiplied_by_one_Then_it_is_unchanged(amount: Money) -> None:
    assert amount.times(Decimal(1)) == amount


@given(pair())
def Given_two_amounts_When_added_in_either_order_Then_the_sum_is_the_same(
    amounts: tuple[Money, Money],
) -> None:
    left, right = amounts
    assert left.plus(right) == right.plus(left)


@given(pair())
def Given_two_amounts_When_one_is_added_and_removed_Then_the_other_returns(
    amounts: tuple[Money, Money],
) -> None:
    left, right = amounts
    assert left.plus(right).minus(right) == left


@given(pair())
def Given_two_amounts_When_ordered_Then_the_ordering_agrees_with_their_difference(
    amounts: tuple[Money, Money],
) -> None:
    left, right = amounts
    difference = left.minus(right)

    if left < right:
        assert difference.is_negative
    elif right < left:
        assert difference.is_positive
    else:
        assert difference.is_zero


@given(money())
def Given_any_amount_When_scaled_with_rounding_Then_it_stays_within_half_a_minor_unit(
    amount: Money,
) -> None:
    multiplier = Decimal("1.005")
    rounded = amount.times_rounded(multiplier)
    # Half a minor unit, not a whole one. Rounding half up to N places can never land further
    # than half a unit from the exact product, and asserting the looser bound let a mutation to
    # ROUND_UP or ROUND_CEILING pass every generated case.
    half_a_minor_unit = Decimal(1).scaleb(-amount.currency.minor_units) / 2

    assert rounded.currency is amount.currency
    assert abs(rounded.amount - amount.amount * multiplier) <= half_a_minor_unit


@given(money())
def Given_an_amount_needing_no_rounding_When_scaled_Then_both_multiplications_agree(
    amount: Money,
) -> None:
    assert amount.times(Decimal(3)) == amount.times_rounded(Decimal(3))


@given(money())
def Given_any_amount_When_rendered_Then_the_text_carries_the_currency_and_full_scale(
    amount: Money,
) -> None:
    assert str(amount) == f"{amount.amount} {amount.currency.name}"
    assert amount.amount.as_tuple().exponent == -amount.currency.minor_units


@given(money())
def Given_any_amount_When_used_as_a_dict_key_Then_equal_amounts_collide(amount: Money) -> None:
    # A frozen dataclass promises hashability; nothing asserted it, and Position and Budget
    # both put Money in collections.
    same = Money(amount.amount, amount.currency)

    assert {amount: "first"} | {same: "second"} == {amount: "second"}
