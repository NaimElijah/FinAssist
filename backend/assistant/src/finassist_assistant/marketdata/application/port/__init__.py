"""QuoteFeedPort as typing.Protocol - one port, many possible feeds."""

from typing import Protocol

from finassist_assistant.marketdata.domain.model import Quote, Ticker

__all__ = ["QuoteFeedPort", "QuoteUnavailableError"]


class QuoteUnavailableError(LookupError):
    """The feed has nothing for this instrument."""

    def __init__(self, ticker: Ticker) -> None:
        super().__init__(f"The feed has no quote for {ticker}")
        self.ticker = ticker


class QuoteFeedPort(Protocol):
    """A price feed, already speaking marketdata's language.

    The port returns a ``Quote`` rather than the feed's own payload, which puts the translation
    in ``adapters/outbound/feed`` and nowhere else — ADR-007 makes that mandatory, and it is
    where ``normalisation.normalise`` is meant to be called. A port that returned raw strings
    would move the anti-corruption layer into the use case.

    Which feed, and whether its licence permits this use, is still an open question. The
    boundary exists so answering it later touches one package.
    """

    def latest(self, ticker: Ticker) -> Quote:
        """The most recent quote the feed holds.

        Freshness is not promised — judging it is
        :class:`~finassist_assistant.marketdata.application.usecase.LatestQuote`'s job, against a
        clock the caller supplies.

        :raises QuoteUnavailableError: if the feed has nothing for the ticker
        """
        ...
