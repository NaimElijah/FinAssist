"""The Money contract, mirroring backend/ledger/.../shared/MoneyTest.java case for case.

ADR-010 accepts that Money is implemented once per language rather than shared, and names
equivalent tests on both sides as the thing that keeps the two honest. Changing behaviour in
one file without changing the other is the drift this pair exists to catch.
"""

from decimal import Decimal

import pytest

from finassist_assistant.shared import Currency, CurrencyMismatchError, Money


def Given_an_amount_with_fewer_decimals_When_constructed_Then_it_is_normalised() -> None:
    # Asserted through str(), not ==. Decimal equality is numeric, so
    # Decimal("12.5") == Decimal("12.50") is True and an equality assertion here passes
    # whether or not the amount was ever normalised - it survived deleting the
    # object.__setattr__ that does the work. The Java mirror does pin this, because
    # BigDecimal.equals is scale-sensitive; only the string form pins it on this side.
    assert str(Money.of("12.5", Currency.ILS)) == "12.50 ILS"
    assert Money.of("12.5", Currency.ILS).amount.as_tuple().exponent == -2
    assert Money.of("12.5", Currency.ILS) == Money.of("12.50", Currency.ILS)


def Given_a_zero_decimal_currency_When_constructed_Then_it_carries_no_decimals() -> None:
    assert str(Money.of("120", Currency.JPY)) == "120 JPY"
    assert Money.of("120", Currency.JPY).amount.as_tuple().exponent == 0


def Given_an_amount_with_too_many_decimals_When_constructed_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError, match="more precision"):
        Money.of("12.505", Currency.ILS)


# Far longer than any refusal needs, and far shorter than an amount expanded digit by digit.
MESSAGE_BOUND = 1_000


@pytest.mark.parametrize("amount", ["1E-2147483647", "1E-1000000"])
def Given_an_amount_at_a_vast_negative_exponent_When_parsed_Then_it_is_refused_with_a_short_message(
    amount: str,
) -> None:
    # The mirror of the Java case, where writing the amount out in full threw OutOfMemoryError in
    # place of the refusal. str() of a Decimal keeps the exponent, so this side always passed.
    with pytest.raises(ValueError, match="more precision") as refused:
        Money.of(amount, Currency.USD)

    assert type(refused.value) is ValueError
    assert len(str(refused.value)) < MESSAGE_BOUND


@pytest.mark.parametrize(
    ("amount", "currency"),
    [
        ("1E+2147483647", Currency.USD),
        ("1E+2147483646", Currency.USD),
        ("1E+2147483648", Currency.JPY),
    ],
    ids=[
        "exponent-past-a-java-int",
        "minor-units-past-a-java-int",
        "zero-decimal-at-the-int-minimum",
    ],
)
def Given_minor_units_overflowing_a_java_int_When_parsed_Then_it_is_refused_for_its_precision(
    amount: str, currency: Currency
) -> None:
    # Each scale fits a Java int, but the ledger's setScale computes the minor units minus the
    # scale past one and refuses the amount; the shared vectors pin the first case on both sides.
    # This side accepted all three, by building a coefficient of over two billion digits.
    with pytest.raises(ValueError, match="more precision") as refused:
        Money.of(amount, currency)

    assert type(refused.value) is ValueError
    assert len(str(refused.value)) < MESSAGE_BOUND


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


def Given_an_amount_past_28_digits_When_negated_Then_every_digit_is_kept() -> None:
    # Unary minus rounds to the context, and Python's default keeps 28 significant digits, so
    # this came back as -1.000000000000000000000000000E+29 - a different amount that Money then
    # accepted. BigDecimal.negate never rounds (#70).
    amount = Money.of("99999999999999999999999999999.99", Currency.USD)

    assert str(amount.negate()) == "-99999999999999999999999999999.99 USD"


def Given_zero_When_negated_Then_it_is_zero_without_a_sign() -> None:
    # Decimal has a negative zero and BigDecimal has none, so a negation that kept the sign of
    # zero would render -0.00 here where Java renders 0.00.
    assert str(Money.zero(Currency.USD).negate()) == "0.00 USD"


def Given_zero_When_asked_Then_it_is_zero_and_neither_positive_nor_negative() -> None:
    zero = Money.zero(Currency.ILS)
    assert zero.is_zero
    assert not zero.is_positive
    assert not zero.is_negative


def Given_an_amount_When_multiplied_by_a_quantity_Then_it_scales() -> None:
    assert Money.of("1.25", Currency.USD).times(Decimal("4")) == Money.of("5.00", Currency.USD)


def Given_a_very_large_amount_When_constructed_Then_it_is_accepted() -> None:
    # Mirrored deliberately: this raised decimal.InvalidOperation - an ArithmeticError, not a
    # ValueError - past the default context precision, where Java's BigDecimal simply succeeds.
    assert Money(Decimal("1E+30"), Currency.USD).amount == Decimal("1E+30")


@pytest.mark.parametrize(
    "amount",
    ["1E+1000000", "1" + "0" * 1_000_001],
    ids=["exponent-past-a-million", "million-and-two-digits"],
)
def Given_an_amount_past_a_million_integer_digits_When_constructed_Then_it_is_accepted(
    amount: str,
) -> None:
    # Java's BigDecimal holds any such amount. Python's default context stops the exponent at
    # 999999, and quantize then raised decimal.InvalidOperation - an ArithmeticError, not the
    # ValueError this module promises, so it was answered 500 rather than refused.
    money = Money(Decimal(amount), Currency.USD)

    assert money.amount == Decimal(amount)


def Given_a_product_past_a_million_integer_digits_When_times_rounded_Then_it_is_accepted() -> None:
    # The same default exponent limit, reached by the product rather than the amount, raised
    # decimal.Overflow, which is no ValueError either.
    product = Money.of("1.00", Currency.USD).times_rounded(Decimal("1E+1000000"))

    assert product.amount == Decimal("1E+1000000")


def Given_a_fractional_multiplier_When_times_rounded_Then_it_rounds_half_up() -> None:
    assert Money.of("12.33", Currency.USD).times_rounded(Decimal("1.5")) == Money.of(
        "18.50", Currency.USD
    )


def Given_a_multiplication_needing_rounding_When_applied_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError):
        Money.of("0.01", Currency.USD).times(Decimal("0.5"))


@pytest.mark.parametrize("multiplier", ["1E-2147483647", "0E-2147483647"])
@pytest.mark.parametrize("operation", ["times", "times_rounded"])
def Given_a_product_scale_past_an_int_When_multiplied_Then_it_is_refused_with_a_short_message(
    operation: str, multiplier: str
) -> None:
    # The mirror of the Java pair. BigDecimal refuses a product whose scale overflows an int
    # whenever the amount is not zero, even by a zero multiplier; this side returned 0.00 for
    # times_rounded and for a zero multiplier, so the two languages answered one input apart.
    amount = Money.of("12.50", Currency.USD)

    with pytest.raises(ValueError, match="more precision") as refused:
        getattr(amount, operation)(Decimal(multiplier))

    assert type(refused.value) is ValueError
    assert len(str(refused.value)) < MESSAGE_BOUND


# USD's two minor units plus 2147483645 places make 2**31 - 1, the largest scale a Java int holds.
ZERO_AT_THE_LARGEST_SCALE = "0E-2147483645"


@pytest.mark.parametrize("operation", ["times", "times_rounded"])
def Given_a_zero_product_at_the_largest_scale_an_int_holds_When_multiplied_Then_it_is_zero(
    operation: str,
) -> None:
    # BigDecimal.multiply accepts this scale, and a zero product rescales to 0.00 without the
    # power of ten a nonzero one would need, so the ledger answers 0.00 as well.
    product = getattr(Money.of("12.50", Currency.USD), operation)(
        Decimal(ZERO_AT_THE_LARGEST_SCALE)
    )

    assert product == Money.zero(Currency.USD)


@pytest.mark.parametrize("multiplier", ["0E-2147483646", "1E-2147483646"])
@pytest.mark.parametrize("operation", ["times", "times_rounded"])
def Given_a_product_scale_one_past_the_largest_an_int_holds_When_multiplied_Then_it_is_refused(
    operation: str, multiplier: str
) -> None:
    with pytest.raises(ValueError, match="its scale overflows a Java int"):
        getattr(Money.of("12.50", Currency.USD), operation)(Decimal(multiplier))


def Given_a_non_finite_amount_When_constructed_Then_it_is_rejected() -> None:
    with pytest.raises(ValueError, match="finite"):
        Money(Decimal("NaN"), Currency.ILS)


def Given_a_very_large_product_When_times_rounded_Then_it_is_accepted() -> None:
    # Mirrors the Java kernel, whose timesRounded uses unbounded BigDecimal arithmetic and
    # simply succeeds. times() already handles this; times_rounded did not, because its
    # context precision was a fixed 60 rather than sized to the value the way __post_init__
    # sizes it. The failure was decimal.InvalidOperation - an ArithmeticError, not the
    # ValueError this module's contract promises - so a caller catching ValueError missed it.
    huge = Money(Decimal("1E+30"), Currency.USD)

    assert str(huge.times_rounded(Decimal("1E+30"))) == "1" + "0" * 60 + ".00 USD"


def Given_two_amounts_in_one_currency_When_ordered_Then_both_comparisons_answer() -> None:
    ten = Money.of("10.00", Currency.ILS)
    twelve = Money.of("12.00", Currency.ILS)

    assert ten < twelve
    assert ten <= twelve
    assert ten <= Money.of("10.00", Currency.ILS)
    assert not twelve <= ten


def Given_two_currencies_When_ordered_with_le_Then_it_is_refused() -> None:
    with pytest.raises(CurrencyMismatchError, match="no conversion"):
        _ = Money.of("10.00", Currency.ILS) <= Money.of("10.00", Currency.USD)


@pytest.mark.parametrize("multiplier", ["NaN", "sNaN", "Infinity", "-Infinity"])
@pytest.mark.parametrize("operation", ["times", "times_rounded"])
def Given_a_non_finite_multiplier_When_multiplied_Then_it_is_refused(
    operation: str, multiplier: str
) -> None:
    # Parametrised over all four non-finite values because the first version of this test used
    # NaN alone and passed: a quiet NaN reached Money's own finiteness check, while an infinity
    # or a signalling NaN escaped as decimal.InvalidOperation - an ArithmeticError, not the
    # ValueError this module's contract promises. Java has no counterpart case; BigDecimal
    # cannot represent any of these.
    money = Money.of("10.00", Currency.USD)

    with pytest.raises(ValueError, match="finite"):
        getattr(money, operation)(Decimal(multiplier))


@pytest.mark.parametrize(
    "text",
    [
        " 12.50 ",
        "12.50\n",
        "1_000.00",
        "abc",
        "",
        ".",
        "1e",
        "--1",
        "+-1",
        "0x10",
        "1,000",
        "12.50d",
        "NaN",
        "Infinity",
        "sNaN",
        "1E-2147483648",
        "1E+9999999999999999999",
        "\U0001d7cf",
    ],
    ids=[
        "padded",
        "trailing-newline",
        "underscore",
        "letters",
        "empty",
        "point-alone",
        "exponent-without-digits",
        "double-sign",
        "two-signs",
        "hexadecimal",
        "grouping-comma",
        "suffix",
        "nan",
        "infinity",
        "signalling-nan",
        "scale-past-a-java-int",
        "exponent-past-python",
        "digit-outside-the-bmp",
    ],
)
def Given_text_that_new_BigDecimal_refuses_When_parsed_Then_it_is_refused(text: str) -> None:
    # Java has no counterpart case: Money.of there is new BigDecimal(text), whose grammar is the
    # whole contract. Python's Decimal is laxer - it strips whitespace, reads underscores and
    # NaN - and raised decimal.InvalidOperation, an ArithmeticError, for text it could not read.
    with pytest.raises(ValueError, match="not a decimal number"):
        Money.of(text, Currency.USD)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("+1", "1.00"),
        (".5", "0.50"),
        ("+.5", "0.50"),
        ("1.", "1.00"),
        ("1e2", "100.00"),
        ("1E+2", "100.00"),
        ("-0.50", "-0.50"),
        ("\u0661\u0662.\u0665\u0660", "12.50"),
        ("\uff11\uff12", "12.00"),
    ],
    ids=[
        "plus-sign",
        "no-integer-part",
        "signed-no-integer-part",
        "no-fraction-part",
        "exponent",
        "signed-exponent",
        "negative",
        "arabic-indic-digits",
        "fullwidth-digits",
    ],
)
def Given_text_that_new_BigDecimal_accepts_When_parsed_Then_it_is_the_same_amount(
    text: str, expected: str
) -> None:
    # Pinned so that refusing what BigDecimal refuses does not also refuse what it accepts.
    # BigDecimal reads any digit Character.digit knows, in the Basic Multilingual Plane; the wire
    # pattern refuses non-ASCII digits long before anything reaches Money.
    assert str(Money.of(text, Currency.USD)) == f"{expected} USD"
