"""The anti-corruption layer over a price feed.

Which feed is an open question, so there is no client. What exists is the shape one has to fit:
raw fields in, a ``Quote`` out, translated by ``normalisation.normalise`` here and nowhere else
(ADR-007).
"""

from datetime import datetime

from finassist_assistant.marketdata.application.port import QuoteFeedPort, QuoteUnavailableError
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.marketdata.domain.normalisation import normalise
from finassist_assistant.shared import Currency

__all__ = ["InMemoryQuoteFeed"]


class InMemoryQuoteFeed(QuoteFeedPort):
    """Holds quotes in memory. A development seam, not a feed.

    ``publish`` takes the loose, stringly-typed fields a real feed would deliver and puts them
    through the same ``normalise`` a real adapter would, so the translation is exercised rather
    than skipped by handing a ``Quote`` straight in.
    """

    def __init__(self) -> None:
        self._quotes: dict[Ticker, Quote] = {}

    def publish(self, ticker: str, price: str, currency: Currency, as_of: datetime) -> Quote:
        """Translates raw feed fields into a quote and keeps it.

        :raises ValueError: if the price is not a number the currency can represent, the ticker
            is blank, the price is negative, or ``as_of`` carries no timezone
        """
        quote = normalise(ticker, price, currency, as_of)
        self._quotes[quote.ticker] = quote
        return quote

    def latest(self, ticker: Ticker) -> Quote:
        try:
            return self._quotes[ticker]
        except KeyError as missing:
            raise QuoteUnavailableError(ticker) from missing
