"""The Money contract, mirroring backend/ledger/.../shared/MoneyTest.java case for case.

ADR-010 accepts that Money is implemented once per language rather than shared, and names
equivalent tests on both sides as the thing that keeps the two honest. Changing behaviour in
one file without changing the other is the drift this pair exists to catch.
"""

from decimal import Decimal

import pytest

from finassist_assistant.shared import Currency, CurrencyMismatchError, Money


def Given_an_amount_with_fewer_decimals_When_constructed_Then_it_is_normalised() -> None:
    assert Money.of("12.5", Currency.ILS).amount == Decimal("12.50")
    assert Money.of("12.5", Currency.ILS) == Money.of("12.50", Currency.ILS)


def Given_an_amount_with_too_many_decimals_When_constructed_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError, match="more precision"):
        Money.of("12.505", Currency.ILS)


def Given_a_zero_decimal_currency_When_given_decimals_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError):
        Money.of("100.50", Currency.JPY)
    assert Money.of("100", Currency.JPY).amount == Decimal("100")


def Given_two_amounts_in_one_currency_When_added_Then_they_sum() -> None:
    total = Money.of("10.00", Currency.ILS).plus(Money.of("2.50", Currency.ILS))
    assert total == Money.of("12.50", Currency.ILS)


def Given_two_currencies_When_added_Then_it_is_refused() -> None:
    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        Money.of("10.00", Currency.ILS).plus(Money.of("10.00", Currency.USD))


def Given_two_currencies_When_compared_Then_it_is_refused() -> None:
    with pytest.raises(CurrencyMismatchError):
        _ = Money.of("10.00", Currency.ILS) < Money.of("10.00", Currency.EUR)


def Given_an_amount_When_negated_Then_the_sign_flips_and_the_currency_holds() -> None:
    negated = Money.of("12.50", Currency.ILS).negate()
    assert negated == Money.of("-12.50", Currency.ILS)
    assert negated.is_negative
    assert negated.negate() == Money.of("12.50", Currency.ILS)


def Given_zero_When_asked_Then_it_is_zero_and_neither_positive_nor_negative() -> None:
    zero = Money.zero(Currency.ILS)
    assert zero.is_zero
    assert not zero.is_positive
    assert not zero.is_negative


def Given_an_amount_When_multiplied_by_a_quantity_Then_it_scales() -> None:
    assert Money.of("1.25", Currency.USD).times(Decimal("4")) == Money.of("5.00", Currency.USD)


def Given_a_multiplication_needing_rounding_When_applied_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError):
        Money.of("0.01", Currency.USD).times(Decimal("0.5"))


def Given_a_non_finite_amount_When_constructed_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError, match="finite"):
        Money(Decimal("NaN"), Currency.ILS)
