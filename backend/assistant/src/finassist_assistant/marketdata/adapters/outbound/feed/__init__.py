"""The anti-corruption layer over a price feed.

Which feed is an open question, so there is no client. What exists is the shape one has to fit:
raw fields in, a ``Quote`` out, translated by ``normalisation.normalise`` here and nowhere else
(ADR-007).
"""

from datetime import datetime

from finassist_assistant.marketdata.application.port import QuoteUnavailableError
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.marketdata.domain.normalisation import normalise
from finassist_assistant.shared import Currency

__all__ = ["InMemoryQuoteFeed"]


class InMemoryQuoteFeed:
    """Holds quotes in memory. A development seam, not a feed.

    Satisfies ``QuoteFeedPort`` by shape rather than by inheritance, for the reason
    ``InMemoryLedger`` gives.

    ``publish`` takes the loose, stringly-typed fields a real feed would deliver and puts them
    through the same ``normalise`` a real adapter would, so the translation is exercised rather
    than skipped by handing a ``Quote`` straight in.
    """

    def __init__(self) -> None:
        self._quotes: dict[Ticker, Quote] = {}

    def publish(self, ticker: str, price: str, currency: Currency, as_of: datetime) -> Quote:
        """Translates raw feed fields into a quote and keeps it if it is the newest one held.

        A replayed or out-of-order delivery must not make ``latest`` answer with an older price than
        one already held: the port promises the most recent quote, and the staleness window would
        otherwise be applied to the wrong instant.

        :returns: the quote ``latest`` will now answer with, which is the one already held when this
            delivery turns out to be older than it
        :raises ValueError: if the price is not a number the currency can represent, the ticker
            is blank, the price is negative, or ``as_of`` carries no timezone
        """
        quote = normalise(ticker, price, currency, as_of)
        held = self._quotes.get(quote.ticker)
        if held is not None and held.as_of > quote.as_of:
            return held
        self._quotes[quote.ticker] = quote
        return quote

    def latest(self, ticker: Ticker) -> Quote:
        try:
            return self._quotes[ticker]
        except KeyError as missing:
            raise QuoteUnavailableError(ticker) from missing
