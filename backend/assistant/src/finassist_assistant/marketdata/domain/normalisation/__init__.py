"""Turning a raw feed shape into a Quote, and deciding whether one is still usable.

Pure functions with the clock passed in. No feed exists yet — which source to use, and whether
its licence permits this use, is still an open question in the requirements — so what lives here
is the shape a feed must be translated into, not a client for one.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation

from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.shared import Currency, Money


def is_stale(quote: Quote, now: datetime, max_age: timedelta) -> bool:
    """Whether a quote is too old to use.

    The current time is an argument, never ``datetime.now()``. A domain function that reads a
    clock cannot be tested without freezing time, and two calls that ought to agree would not.

    :raises ValueError: if ``now`` is naive, or ``max_age`` is not positive
    """
    # The tzinfo guard lives in age(), which is the function that actually uses `now`; repeating
    # it here would leave one of the two dead on every path.
    if max_age <= timedelta(0):
        raise ValueError(f"max_age must be positive but was {max_age}")
    return age(quote, now) > max_age


def age(quote: Quote, now: datetime) -> timedelta:
    """How old a quote is. Negative when the quote is timestamped in the future."""
    if now.tzinfo is None:
        raise ValueError("now must carry a timezone; naive datetimes are ambiguous")
    return now - quote.as_of


def normalise(ticker: str, price: str, currency: Currency, as_of: datetime) -> Quote:
    """Builds a Quote from the string shapes a feed hands over.

    Prices arrive from feeds as text, and must stay text until they become a ``Decimal``:
    parsing through ``float`` would lose exactness before the value is ever stored.

    :raises ValueError: if the price is not a number the currency can represent
    """
    try:
        amount = Decimal(price)
    except InvalidOperation as cause:
        raise ValueError(f"Price {price!r} is not a decimal number") from cause

    return Quote(Ticker(ticker), Money(amount, currency), as_of)


__all__ = ["age", "is_stale", "normalise"]
