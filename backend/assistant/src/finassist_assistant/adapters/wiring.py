"""Assembles the assistant's use cases, and holds the one translation between two contexts.

This belongs to the composition root for the same reason the ledger's does. ``portfolio`` needs a
price and ``marketdata`` has one, and the architecture rules forbid either from importing the
other — so the class that turns a ``Symbol`` into a ``Ticker`` and a ``Quote`` into a ``Money``
can live in neither. It lives here, where seeing two contexts at once is the job (ADR-022), and
``tests/test_architecture.py`` already forbids any context from importing back into this package.

Everything assembled here is backed by an in-memory adapter. Neither is a client: the assistant
has no ledger client yet, so what exists is the shape one must fit — not because there is
nothing to call. ADR-004 is Accepted and the ledger now serves accounting, budgeting and
planning under `/api/v1`.

``assistance`` is deliberately absent. ``AnswerQuestion`` is written and tested, and wiring it
would mean building an LLM adapter, which would decide by implication whether the owner's
financial data may be sent to a third-party API. The requirements reserve that for its own
decision, and a composition root is the wrong place to settle it quietly.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from finassist_assistant.marketdata.adapters.outbound.feed import InMemoryQuoteFeed
from finassist_assistant.marketdata.application.port import QuoteFeedPort, QuoteUnavailableError
from finassist_assistant.marketdata.application.usecase import LatestQuote, StaleQuoteError
from finassist_assistant.marketdata.domain.model import Ticker
from finassist_assistant.portfolio.adapters.outbound.ledger import InMemoryLedger
from finassist_assistant.portfolio.application.port import PriceUnavailableError, QuotePort
from finassist_assistant.portfolio.application.usecase import (
    DisposeUnits,
    ValueHolding,
    ViewPosition,
)
from finassist_assistant.portfolio.domain.model import Symbol
from finassist_assistant.shared import Money

__all__ = ["Assembly", "Clock", "MarketDataQuotes", "Portfolio", "assemble", "utc_now"]

Clock = Callable[[], datetime]
"""Reads the current instant. A parameter everywhere else; supplied once, here."""


def utc_now() -> datetime:
    """The wall clock, read in the only layer permitted to read it.

    Every domain function and every use case takes the time as an argument, which is what makes
    them testable without freezing anything. Somebody still has to know what time it is, and that
    somebody is the composition root.
    """
    return datetime.now(UTC)


@dataclass(frozen=True, slots=True)
class MarketDataQuotes:
    """Portfolio's ``QuotePort``, answered by market data.

    The translation is small and it is the point: a ``Symbol`` is portfolio's word for an
    instrument and a ``Ticker`` is market data's, and they are separate types precisely so that
    neither context has to agree with the other about what an instrument is.

    Both failures collapse into :class:`PriceUnavailableError`. Portfolio cannot name
    ``StaleQuoteError`` without importing another context, and it would have nothing different to
    do with it if it could — a holding cannot be valued either way.
    """

    quotes: LatestQuote
    clock: Clock

    def unit_price(self, symbol: Symbol) -> Money:
        try:
            quote = self.quotes.of(Ticker(str(symbol)), self.clock())
        except (QuoteUnavailableError, StaleQuoteError) as unusable:
            raise PriceUnavailableError(symbol) from unusable
        return quote.price


@dataclass(frozen=True, slots=True)
class Portfolio:
    """The portfolio use cases, ready to call."""

    view_position: ViewPosition
    value_holding: ValueHolding
    dispose_units: DisposeUnits


@dataclass(frozen=True, slots=True)
class Assembly:
    """Everything the composition root built.

    The two adapters are exposed alongside the use cases because they are the only way to put
    data in while there is no client to fetch it. That is a property of a development seam, and
    it disappears with the seam.

    ``feed`` is typed by the port it satisfies rather than by ``InMemoryQuoteFeed`` alone.
    ``assemble`` normally wires the latter, but the development seed
    (:mod:`~finassist_assistant.adapters.seed`) wires its own object that satisfies the same
    port and never goes stale, and ``Assembly`` has one shape for both rather than a second
    field nothing else would read. A caller that needs the concrete seam back — ``publish``,
    which only ``InMemoryQuoteFeed`` has — narrows with ``isinstance`` first; nothing here casts
    the difference away.
    """

    ledger: InMemoryLedger
    feed: QuoteFeedPort
    portfolio: Portfolio


def assemble(clock: Clock = utc_now, feed: QuoteFeedPort | None = None) -> Assembly:
    """Builds every adapter and use case, wiring them by constructor.

    :param clock: how to read the current instant. Tests pass a fixed one, which is the whole
        reason it is an argument.
    :param feed: the quote source backing every valuation. Defaults to a fresh, empty
        ``InMemoryQuoteFeed`` — the development seam ``assembly.feed.publish(...)`` fills in
        tests. A caller assembling the development seed passes its own feed instead, so the same
        object this function wires into ``ValueHolding`` is the one ``Assembly.feed`` exposes.
    """
    ledger = InMemoryLedger()
    resolved_feed: QuoteFeedPort = feed if feed is not None else InMemoryQuoteFeed()

    # Annotated, so mypy checks that the translation really satisfies the port it claims to.
    quotes: QuotePort = MarketDataQuotes(LatestQuote(resolved_feed), clock)

    return Assembly(
        ledger=ledger,
        feed=resolved_feed,
        portfolio=Portfolio(
            view_position=ViewPosition(ledger),
            value_holding=ValueHolding(ledger, quotes),
            dispose_units=DisposeUnits(ledger),
        ),
    )
