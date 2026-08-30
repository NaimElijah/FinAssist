"""An exact monetary amount in one currency."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal, localcontext

from finassist_assistant.shared.currency import Currency


class CurrencyMismatchError(ValueError):
    """Raised when an operation would combine amounts in different currencies.

    FinAssist holds no exchange rates and performs no conversion (ADR-012), so adding ILS to
    USD has no defined answer. Failing loudly is the only honest option: silently picking one
    currency would produce a total that looks right and means nothing.
    """

    def __init__(self, left: Currency, right: Currency) -> None:
        super().__init__(
            f"Cannot combine {left.name} with {right.name}; FinAssist performs no conversion"
        )


def _precision_for(value: Decimal) -> int:
    """Significant digits an exact representation of ``value`` needs.

    Every digit already present, plus the zeros a positive exponent implies — ``1E+30`` is one
    digit and thirty zeros. Sizing off the digit tuple alone under-counts that case badly.
    """
    if not value.is_finite():
        return 1
    digits, exponent = value.as_tuple()[1:]
    return len(digits) + max(int(exponent), 0) + 1


@dataclass(frozen=True, slots=True)
class Money:
    """A ``Decimal`` amount and its currency.

    ``Decimal``, never ``float``: a binary floating-point type cannot represent 0.10, and a
    figure that drifts by a cent per thousand is wrong in a way no later test will find.

    The amount is normalised to the currency's minor units on construction, so ``12.5`` and
    ``12.50`` are one value. An amount carrying more precision than the currency admits is
    rejected rather than rounded — rounding here would be a silent decision about money.

    Arithmetic is closed over a single currency; combining two raises
    :class:`CurrencyMismatchError`.

    The Java counterpart is ``ledger.shared.Money`` and ``tests/test_money.py`` mirrors
    ``MoneyTest.java`` case for case.
    """

    amount: Decimal
    currency: Currency

    def __post_init__(self) -> None:
        if not self.amount.is_finite():
            raise ValueError(f"Amount must be finite but was {self.amount}")

        exponent = Decimal(1).scaleb(-self.currency.minor_units)
        # The context precision is widened to fit this particular value. Python's default of 28
        # significant digits makes quantize raise InvalidOperation - an ArithmeticError, not a
        # ValueError - for a large amount, while Java's BigDecimal has no such limit and simply
        # succeeds. That divergence would have escaped every caller catching ValueError.
        with localcontext() as context:
            context.prec = _precision_for(self.amount) + self.currency.minor_units
            normalised = self.amount.quantize(exponent, rounding=ROUND_DOWN)
        if normalised != self.amount:
            raise ValueError(
                f"Amount {self.amount} carries more precision than {self.currency.name} "
                f"admits ({self.currency.minor_units} decimal places); "
                "round it deliberately before constructing Money"
            )
        object.__setattr__(self, "amount", normalised)

    @classmethod
    def of(cls, amount: str, currency: Currency) -> Money:
        """Builds an amount from decimal text, e.g. ``Money.of("12.50", Currency.ILS)``.

        Text rather than a float literal on purpose: ``Decimal(12.50)`` captures the binary
        approximation of 12.5, which is not 12.50.
        """
        return cls(Decimal(amount), currency)

    @classmethod
    def zero(cls, currency: Currency) -> Money:
        return cls(Decimal(0), currency)

    def plus(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.amount + other.amount, self.currency)

    def minus(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.amount - other.amount, self.currency)

    def negate(self) -> Money:
        return Money(-self.amount, self.currency)

    def times(self, multiplier: Decimal) -> Money:
        """Multiplies by a count, exactly. Never by another Money — that is not money.

        :raises ValueError: if the product needs rounding to fit the currency
        """
        return Money(self.amount * multiplier, self.currency)

    def times_rounded(self, multiplier: Decimal) -> Money:
        """Multiplies by a count, rounding the product to the currency's minor units.

        The one place rounding is allowed, and it is explicit in the name. A fractional quantity —
        1.5 units at 12.33 — has a mathematically exact product of 18.495 that no two-decimal
        currency can express, so a total cost has to be rounded somewhere. Doing it here, half up,
        in a method the caller has to choose, beats :meth:`times` throwing on perfectly ordinary
        fractional holdings.
        """
        with localcontext() as context:
            # Sized to this particular product, for the reason __post_init__ is sized to its
            # particular value. A fixed precision raises decimal.InvalidOperation - an
            # ArithmeticError, not the ValueError this module's contract promises - once the
            # product outgrows it, while Java's timesRounded uses unbounded BigDecimal and
            # succeeds. An exact product needs at most the significant digits of both operands,
            # plus the zeros their positive exponents imply, plus the minor units.
            context.prec = (
                _precision_for(self.amount) + _precision_for(multiplier) + self.currency.minor_units
            )
            product = self.amount * multiplier
            rounded = product.quantize(
                Decimal(1).scaleb(-self.currency.minor_units), rounding=ROUND_HALF_UP
            )
        return Money(rounded, self.currency)

    @property
    def is_zero(self) -> bool:
        return self.amount == 0

    @property
    def is_positive(self) -> bool:
        return self.amount > 0

    @property
    def is_negative(self) -> bool:
        return self.amount < 0

    def __lt__(self, other: Money) -> bool:
        self._require_same_currency(other)
        return self.amount < other.amount

    def __le__(self, other: Money) -> bool:
        self._require_same_currency(other)
        return self.amount <= other.amount

    def __str__(self) -> str:
        return f"{self.amount} {self.currency.name}"

    def _require_same_currency(self, other: Money) -> None:
        if self.currency is not other.currency:
            raise CurrencyMismatchError(self.currency, other.currency)
