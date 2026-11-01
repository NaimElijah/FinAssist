"""Frozen values the market-data context operates on: an instrument and a quote."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from finassist_assistant.shared import Money


@dataclass(frozen=True, slots=True)
class Ticker:
    """An instrument's symbol, in this context's own vocabulary.

    The portfolio context has its own ``Symbol`` and this is deliberately not it. Sharing one
    type across two contexts is the coupling the architecture tests forbid; translating between
    them is the anti-corruption layer's job.

    Surrounding whitespace is stripped and the text upper-cased on construction.

    :raises ValueError: if the text is blank once stripped
    """

    value: str

    def __post_init__(self) -> None:
        stripped = self.value.strip().upper()
        if not stripped:
            raise ValueError("A ticker must not be blank")
        object.__setattr__(self, "value", stripped)

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True, slots=True)
class Instrument:
    """Something that can be priced.

    :raises ValueError: if the name is blank once stripped
    """

    ticker: Ticker
    name: str

    def __post_init__(self) -> None:
        name = self.name.strip()
        if not name:
            raise ValueError("An instrument must have a name")
        # Normalised, not merely validated. Ticker, Symbol, Account, Category and Claim all strip
        # and assign back; without it two instruments differing only in padding are not equal.
        object.__setattr__(self, "name", name)


@dataclass(frozen=True, slots=True)
class Quote:
    """A price for an instrument at a moment.

    ``as_of`` is the moment the *price* was true, not the moment it was fetched. A quote with no
    timestamp cannot be judged stale, so there is no default.

    :raises ValueError: if the price is negative, or ``as_of`` carries no timezone
    """

    ticker: Ticker
    price: Money
    as_of: datetime

    def __post_init__(self) -> None:
        if self.price.is_negative:
            raise ValueError(f"A quoted price cannot be negative but was {self.price}")
        if self.as_of.tzinfo is None:
            raise ValueError(
                "A quote timestamp must carry a timezone; naive datetimes are ambiguous"
            )
