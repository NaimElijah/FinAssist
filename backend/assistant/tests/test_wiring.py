"""The composition root, assembled.

Every other test in this layer constructs its use cases by hand, which is what proves they need
no wiring to run. This is the complement: it asserts that the pieces fit, and that the one
translation between two contexts really carries a price across the boundary neither may import.
"""

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pytest

from finassist_assistant.adapters.wiring import Assembly, MarketDataQuotes, assemble, utc_now
from finassist_assistant.marketdata.adapters.outbound.feed import InMemoryQuoteFeed
from finassist_assistant.marketdata.application.usecase import LatestQuote
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.portfolio.application.port import PriceUnavailableError
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

VOO = Symbol("VOO")
NOON = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)
LOT = Lot(VOO, Decimal("10"), Money.of("100.00", Currency.USD), date(2026, 1, 10))


def _seam(assembly: Assembly) -> InMemoryQuoteFeed:
    """Narrows ``Assembly.feed`` back to the development seam's own API.

    Every test here builds its assembly with ``assemble()``'s default feed, so the widened
    ``QuoteFeedPort`` type is always an ``InMemoryQuoteFeed`` in this file — checked with
    ``isinstance``, never assumed with a cast.
    """
    feed = assembly.feed
    assert isinstance(feed, InMemoryQuoteFeed)
    return feed


def Given_the_assembly_When_a_holding_is_valued_Then_the_price_crosses_the_context_boundary() -> (
    None
):
    assembly = assemble(clock=lambda: NOON)
    assembly.ledger.add(LOT)
    _seam(assembly).publish("voo", "250.00", Currency.USD, NOON - timedelta(minutes=5))

    valuation = assembly.portfolio.value_holding.of(VOO)

    assert valuation.market_value == Money.of("2500.00", Currency.USD)
    assert valuation.unrealised_gain == Money.of("1500.00", Currency.USD)


def Given_the_assembly_When_a_position_is_read_Then_it_uses_the_same_ledger() -> None:
    assembly = assemble(clock=lambda: NOON)
    assembly.ledger.add(LOT)

    assert assembly.portfolio.view_position.of(VOO).quantity == Decimal("10")


def Given_a_symbol_When_a_price_is_asked_for_Then_it_is_translated_into_a_ticker() -> None:
    # The whole point of the translation: a Symbol is portfolio's word for an instrument and a
    # Ticker is market data's, and neither context may name the other's type.
    assembly = assemble(clock=lambda: NOON)
    _seam(assembly).publish("voo", "250.00", Currency.USD, NOON - timedelta(minutes=5))
    quotes = MarketDataQuotes(LatestQuote(assembly.feed), lambda: NOON)

    assert quotes.unit_price(VOO) == Money.of("250.00", Currency.USD)


def Given_no_quote_for_the_symbol_When_a_price_is_asked_for_Then_portfolio_sees_its_own_error() -> (
    None
):
    quotes = MarketDataQuotes(LatestQuote(assemble().feed), lambda: NOON)

    with pytest.raises(PriceUnavailableError, match="No usable price"):
        quotes.unit_price(VOO)


def Given_only_a_stale_quote_When_a_price_is_asked_for_Then_it_is_reported_as_unavailable() -> None:
    # Portfolio cannot name StaleQuoteError without importing another context, and would have
    # nothing different to do with it if it could: a holding cannot be valued either way.
    assembly = assemble(clock=lambda: NOON)
    _seam(assembly).publish("voo", "250.00", Currency.USD, NOON - timedelta(days=3))
    quotes = MarketDataQuotes(LatestQuote(assembly.feed), lambda: NOON)

    with pytest.raises(PriceUnavailableError):
        quotes.unit_price(VOO)


def Given_a_feed_price_that_is_not_a_number_When_published_Then_it_is_refused_at_the_edge() -> None:
    with pytest.raises(ValueError, match="not a decimal number"):
        _seam(assemble()).publish("voo", "two hundred", Currency.USD, NOON)


def Given_the_default_clock_When_read_Then_it_carries_a_timezone() -> None:
    # A naive instant would be refused by every staleness check downstream.
    assert utc_now().tzinfo is not None


def Given_an_older_quote_published_after_a_newer_one_When_read_Then_the_newer_one_is_kept() -> None:
    # A replayed or out-of-order delivery. Overwriting would make latest() answer with a stale price
    # and apply the freshness window to the wrong instant.
    feed = _seam(assemble())
    feed.publish("voo", "250.00", Currency.USD, NOON - timedelta(minutes=5))
    feed.publish("voo", "240.00", Currency.USD, NOON - timedelta(minutes=30))

    assert feed.latest(Ticker("VOO")).price == Money.of("250.00", Currency.USD)


def Given_an_older_quote_published_When_it_returns_Then_it_answers_with_what_is_held() -> None:
    feed = _seam(assemble())
    newest = feed.publish("voo", "250.00", Currency.USD, NOON - timedelta(minutes=5))

    assert feed.publish("voo", "240.00", Currency.USD, NOON - timedelta(minutes=30)) == newest


def Given_a_newer_quote_published_When_read_Then_it_replaces_the_older_one() -> None:
    feed = _seam(assemble())
    feed.publish("voo", "240.00", Currency.USD, NOON - timedelta(minutes=30))
    feed.publish("voo", "250.00", Currency.USD, NOON - timedelta(minutes=5))

    assert feed.latest(Ticker("VOO")).price == Money.of("250.00", Currency.USD)


# --- assemble() honouring a caller-supplied feed --------------------------------------------


class _FixedFeed:
    """A minimal QuoteFeedPort, satisfied by shape, to prove assemble() wires what it is given."""

    def __init__(self, price: Money) -> None:
        self._price = price

    def latest(self, ticker: Ticker) -> Quote:
        return Quote(ticker, self._price, as_of=NOON)


def Given_a_feed_passed_to_assemble_When_a_holding_is_valued_Then_that_feed_priced_it() -> None:
    """The object assemble() is given backs both Assembly.feed and the price a valuation reads:
    the same instance, not a copy, which is what lets a caller compose its own feed (ADR-021)."""
    fixed = _FixedFeed(Money.of("99.00", Currency.USD))
    assembly = assemble(clock=lambda: NOON, feed=fixed)
    assembly.ledger.add(LOT)

    assert assembly.feed is fixed
    assert assembly.portfolio.value_holding.of(VOO).unit_price == Money.of("99.00", Currency.USD)
