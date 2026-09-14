"""Transport models for the portfolio endpoints, and their mapping from portfolio's own values.

Pydantic lives here and nowhere inward (ADR-007). The domain's frozen dataclasses never learn what
the wire looks like, and nothing here holds a rule: every model is built from a value the domain
has already validated.

The wire follows ADR-028. Every decimal is a JSON string and never a JSON number, because most
parsers read a number into a binary float, and because a string keeps the scale that makes an
amount ``"12.50"`` rather than ``12.5``. Members are camelCase, as the ledger's are, so one client
reads both services alike. Each class docstring becomes its schema's description in the contract.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Annotated, Final, Literal

from pydantic import BaseModel, ConfigDict, StringConstraints
from pydantic.alias_generators import to_camel

from finassist_assistant.portfolio.application.usecase import Disposition, Valuation
from finassist_assistant.portfolio.domain.model import Lot, Position, Symbol
from finassist_assistant.shared import Money

__all__ = [
    "DECIMAL_PATTERN",
    "CurrencyCode",
    "DecimalString",
    "DispositionModel",
    "LotModel",
    "MoneyModel",
    "PositionModel",
    "ValuationModel",
]

DECIMAL_PATTERN: Final = r"^-?\d+(\.\d+)?$"
"""A decimal written out in full: an optional sign, digits, and an optional fraction.

Anchored at both ends, the ledger's pattern exactly. Neither a JSON Schema ``pattern`` nor
pydantic's is anchored implicitly, so without them ``"12abc"`` would match. No exponent and no digit
grouping: the text is the number as ``Decimal`` prints it, and nothing a client has to interpret.
"""

DecimalString = Annotated[str, StringConstraints(pattern=DECIMAL_PATTERN)]
"""A decimal on the wire."""

CurrencyCode = Literal["ILS", "USD", "EUR", "GBP", "JPY"]
"""The currencies the wire carries, which a test holds equal to ``Currency``'s codes.

A ``Literal`` rather than the enum, so the contract lists the codes as the ledger's does, instead of
an enum whose values are ``(code, minor_units)`` pairs.
"""


def _decimal(value: Decimal) -> str:
    """Never exponent form, which ``str(Decimal)`` can produce and the pattern refuses."""
    return format(value, "f")


class _WireModel(BaseModel):
    """camelCase on the wire and snake_case in Python, immutable once built, and silent about input.

    A rejected model's ``ValidationError`` text would otherwise quote the value it rejected, and
    these models hold the owner's money; anything that prints the error, from a chained traceback
    to a library's own log line, would print the amount with it.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_name=True,
        frozen=True,
        hide_input_in_errors=True,
    )


class MoneyModel(_WireModel):
    """An exact amount in one currency, the amount a decimal string at the currency's scale."""

    amount: DecimalString
    currency: CurrencyCode

    @classmethod
    def of(cls, money: Money) -> MoneyModel:
        """The wire form of an amount. ``Money`` already holds it at its currency's scale.

        Validated from a mapping rather than constructed, because the currency's code is a ``str``
        to the type checker. A code missing from ``CurrencyCode`` fails here with pydantic's
        ``ValidationError``, which the composition root answers 500: the assistant's own bug.
        """
        return cls.model_validate(
            {"amount": _decimal(money.amount), "currency": money.currency.code}
        )


class PositionModel(_WireModel):
    """What is held in one instrument, and what it cost in total."""

    symbol: str
    quantity: DecimalString
    cost: MoneyModel

    @classmethod
    def of(cls, position: Position) -> PositionModel:
        """The wire form of a position."""
        return cls(
            symbol=str(position.symbol),
            quantity=_decimal(position.quantity),
            cost=MoneyModel.of(position.cost),
        )


class ValuationModel(_WireModel):
    """A holding valued at the latest unit price, and the gain that price implies."""

    symbol: str
    quantity: DecimalString
    cost: MoneyModel
    unit_price: MoneyModel
    market_value: MoneyModel
    unrealised_gain: MoneyModel

    @classmethod
    def of(cls, valuation: Valuation) -> ValuationModel:
        """The wire form of a valuation, with the position it values flattened into it."""
        held = valuation.position
        return cls(
            symbol=str(held.symbol),
            quantity=_decimal(held.quantity),
            cost=MoneyModel.of(held.cost),
            unit_price=MoneyModel.of(valuation.unit_price),
            market_value=MoneyModel.of(valuation.market_value),
            unrealised_gain=MoneyModel.of(valuation.unrealised_gain),
        )


class LotModel(_WireModel):
    """One purchase still held: how many units, at what unit cost, acquired on which date."""

    quantity: DecimalString
    unit_cost: MoneyModel
    acquired_on: date

    @classmethod
    def of(cls, lot: Lot) -> LotModel:
        """The wire form of a lot. Its symbol is the disposition's, so it is not repeated."""
        return cls(
            quantity=_decimal(lot.quantity),
            unit_cost=MoneyModel.of(lot.unit_cost),
            acquired_on=lot.acquired_on,
        )


class DispositionModel(_WireModel):
    """What selling units would realise, and the lots that would remain, oldest first."""

    symbol: str
    quantity: DecimalString
    proceeds: MoneyModel
    cost_of_sale: MoneyModel
    realised_gain: MoneyModel
    remaining: list[LotModel]

    @classmethod
    def of(cls, symbol: Symbol, quantity: Decimal, disposition: Disposition) -> DispositionModel:
        """The wire form of a disposition, naming the symbol and quantity it was priced for.

        A ``Disposition`` does not carry either, because the caller of the use case already has
        both; the response repeats them so it stands on its own.
        """
        return cls(
            symbol=str(symbol),
            quantity=_decimal(quantity),
            proceeds=MoneyModel.of(disposition.proceeds),
            cost_of_sale=MoneyModel.of(disposition.disposal.cost_of_sale),
            realised_gain=MoneyModel.of(disposition.realised_gain),
            remaining=[LotModel.of(lot) for lot in disposition.remaining],
        )
