"""Fictional development data for the portfolio screens, loaded only behind ``ASSISTANT_DEV_SEED``.

Nothing here is real. It exists so the portfolio endpoints have something to answer before a
ledger client or a price feed exists (both open questions in the requirements), and it is off by
default (``Settings.dev_seed``) so a production-shaped run never serves it by accident.

The lots are ordinary on purpose: two lots of the same USD instrument at different dates and unit
costs, so disposing of units visibly consumes the older one first, and one lot priced in a second
currency, so a position in ILS renders alongside one in USD.

The quotes are not published through ``InMemoryQuoteFeed``. Publishing once at start-up would
freeze ``as_of`` at that instant, and ``LatestQuote`` refuses a quote older than
``DEFAULT_MAX_AGE`` (one hour) — a seeded quote published at start-up would make valuation start
answering 503 the moment the process had been up that long. ``SeededQuoteFeed`` stamps ``as_of``
with the clock at *read* time instead, so a seeded price is exactly as fresh an hour into the
process as it is a second in.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from types import MappingProxyType

from finassist_assistant.adapters.wiring import Assembly, Clock, assemble, utc_now
from finassist_assistant.marketdata.application.port import QuoteUnavailableError
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

__all__ = ["SEED_SYMBOLS", "SeededQuoteFeed", "seeded_assembly"]

_LOTS: tuple[Lot, ...] = (
    Lot(Symbol("VOO"), Decimal(10), Money.of("380.00", Currency.USD), date(2024, 3, 15)),
    Lot(Symbol("VOO"), Decimal(5), Money.of("410.00", Currency.USD), date(2025, 1, 10)),
    Lot(Symbol("DEMO"), Decimal(100), Money.of("25.50", Currency.ILS), date(2024, 6, 1)),
)
"""A small, obviously fictional set: two VOO lots so FIFO disposal is visible, one ILS lot so a
second currency renders."""

_PRICES: Mapping[Ticker, Money] = MappingProxyType(
    {
        Ticker("VOO"): Money.of("430.25", Currency.USD),
        Ticker("DEMO"): Money.of("30.00", Currency.ILS),
    }
)
"""One made-up current price per seeded symbol."""

SEED_SYMBOLS: tuple[str, ...] = tuple(sorted({str(lot.symbol) for lot in _LOTS}))
"""Every symbol the seed populates, for the start-up log line. Names only, never an amount."""


@dataclass(frozen=True, slots=True)
class SeededQuoteFeed:
    """A fixed price table that never goes stale, because nothing publishes to it.

    Satisfies ``QuoteFeedPort`` by shape, the way ``InMemoryQuoteFeed`` does: no inheritance, so
    a misspelled method fails whoever calls it rather than silently answering with a Protocol
    stub's ``None``.

    :param prices: one price per ticker. Fixed for the life of the process; nothing here models
        a moving market.
    :param clock: read at every call, not stored once — the whole reason this class exists
        rather than a single ``InMemoryQuoteFeed.publish`` at start-up.
    """

    prices: Mapping[Ticker, Money]
    clock: Clock

    def latest(self, ticker: Ticker) -> Quote:
        """
        :raises QuoteUnavailableError: if the ticker is not one the seed prices
        """
        try:
            price = self.prices[ticker]
        except KeyError as missing:
            raise QuoteUnavailableError(ticker) from missing
        return Quote(ticker, price, as_of=self.clock())


def seeded_assembly(clock: Clock = utc_now) -> Assembly:
    """Wires an :class:`~finassist_assistant.adapters.wiring.Assembly` preloaded with fictional
    holdings and quotes.

    :param clock: how to read the current instant. Threaded through to
        :func:`~finassist_assistant.adapters.wiring.assemble` and to :class:`SeededQuoteFeed`, so
        a test can fix it and a real run reads the wall clock — the same discipline every other
        composition-root function in this package follows.
    """
    assembly = assemble(clock=clock, feed=SeededQuoteFeed(prices=_PRICES, clock=clock))
    for lot in _LOTS:
        assembly.ledger.add(lot)
    return assembly
