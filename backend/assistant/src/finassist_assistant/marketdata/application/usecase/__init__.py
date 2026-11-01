"""Orchestration only."""

from dataclasses import dataclass
from datetime import datetime, timedelta

from finassist_assistant.marketdata.application.port import QuoteFeedPort
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.marketdata.domain.normalisation import age, is_stale

__all__ = ["DEFAULT_MAX_AGE", "LatestQuote", "StaleQuoteError"]

DEFAULT_MAX_AGE = timedelta(hours=1)
"""How old a quote may be before it is refused.

An hour is a starting point, not a finding. It is a named constant and a constructor argument
rather than a literal in a call, so changing it is one edit and reading it needs no archaeology.
"""


class StaleQuoteError(ValueError):
    """The feed answered, but with a price too old, or dated too far ahead, to value anything at."""

    def __init__(self, quote: Quote, now: datetime, max_age: timedelta) -> None:
        elapsed = age(quote, now)
        if elapsed < timedelta(0):
            reason = f"is dated {-elapsed} ahead of the current time"
        else:
            reason = f"is {elapsed} old, past the {max_age} this caller accepts"
        super().__init__(f"The quote for {quote.ticker} {reason}")
        self.quote = quote


@dataclass(frozen=True, slots=True)
class LatestQuote:
    """Fetches a quote and refuses it if it is too old to be worth having.

    A stale price is worse than no price: it values a holding with a number that looks current
    and is not, and nothing downstream can tell the difference. So this raises rather than
    returning something a caller has to remember to check.

    The clock is a parameter, never read here. The domain functions already require it that way,
    and the same discipline one layer out is what lets these cases be tested without freezing
    time.
    """

    feed: QuoteFeedPort
    max_age: timedelta = DEFAULT_MAX_AGE

    def __post_init__(self) -> None:
        """
        :raises ValueError: if ``max_age`` is not positive — a misconfiguration of this service,
            refused at assembly rather than answered as the caller's fault on every valuation
        """
        if self.max_age <= timedelta(0):
            raise ValueError(f"max_age must be positive but was {self.max_age}")

    def of(self, ticker: Ticker, now: datetime) -> Quote:
        """
        :raises QuoteUnavailableError: if the feed has nothing for the ticker
        :raises StaleQuoteError: if the quote is older than ``max_age``, or dated more than
            ``FUTURE_TOLERANCE`` ahead of ``now``
        :raises ValueError: if ``now`` carries no timezone
        """
        quote = self.feed.latest(ticker)
        if is_stale(quote, now, self.max_age):
            raise StaleQuoteError(quote, now, self.max_age)
        return quote
