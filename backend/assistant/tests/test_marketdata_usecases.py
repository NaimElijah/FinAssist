"""Fetching a quote, and refusing one too old to value anything at.

A stale price is worse than no price: it looks current, and nothing downstream can tell that it
is not. So the use case raises rather than returning something a caller has to remember to check.
"""

from datetime import UTC, datetime, timedelta

import pytest

from finassist_assistant.marketdata.application.port import QuoteUnavailableError
from finassist_assistant.marketdata.application.usecase import (
    DEFAULT_MAX_AGE,
    LatestQuote,
    StaleQuoteError,
)
from finassist_assistant.marketdata.domain.model import Quote, Ticker
from finassist_assistant.shared import Currency, Money

VOO = Ticker("VOO")
NOON = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)


class FakeFeed:
    def __init__(self, *quotes: Quote) -> None:
        self.held = {quote.ticker: quote for quote in quotes}
        self.asked: list[Ticker] = []

    def latest(self, ticker: Ticker) -> Quote:
        self.asked.append(ticker)
        try:
            return self.held[ticker]
        except KeyError as missing:
            raise QuoteUnavailableError(ticker) from missing


def quote(minutes_old: int) -> Quote:
    return Quote(VOO, Money.of("250.00", Currency.USD), NOON - timedelta(minutes=minutes_old))


def Given_a_fresh_quote_When_asked_for_Then_it_is_returned() -> None:
    assert LatestQuote(FakeFeed(quote(5))).of(VOO, NOON).price == Money.of("250.00", Currency.USD)


def Given_a_quote_older_than_the_limit_When_asked_for_Then_it_is_refused() -> None:
    with pytest.raises(StaleQuoteError, match="past the"):
        LatestQuote(FakeFeed(quote(90))).of(VOO, NOON)


def Given_a_quote_exactly_at_the_limit_When_asked_for_Then_it_is_still_returned() -> None:
    # is_stale is strictly greater-than, so the boundary is usable. Pinned because a change to
    # >= would quietly shorten every caller's window by nothing they could observe.
    assert LatestQuote(FakeFeed(quote(60))).of(VOO, NOON).ticker == VOO


def Given_a_shorter_limit_When_asked_for_Then_a_quote_that_would_pass_by_default_is_refused() -> (
    None
):
    fresh_enough_by_default = quote(30)
    assert LatestQuote(FakeFeed(fresh_enough_by_default)).of(VOO, NOON) is fresh_enough_by_default

    with pytest.raises(StaleQuoteError):
        LatestQuote(FakeFeed(fresh_enough_by_default), max_age=timedelta(minutes=5)).of(VOO, NOON)


def Given_a_feed_with_nothing_for_the_ticker_When_asked_for_Then_it_is_refused() -> None:
    with pytest.raises(QuoteUnavailableError, match="no quote for"):
        LatestQuote(FakeFeed()).of(VOO, NOON)


def Given_a_naive_clock_When_asked_for_Then_it_is_refused_as_ambiguous() -> None:
    with pytest.raises(ValueError, match="timezone"):
        LatestQuote(FakeFeed(quote(5))).of(VOO, datetime(2026, 3, 5, 12, 0))


def Given_the_default_limit_When_read_Then_it_is_an_hour() -> None:
    assert timedelta(hours=1) == DEFAULT_MAX_AGE


def Given_a_quote_dated_a_year_ahead_When_asked_for_Then_it_is_refused() -> None:
    # A feed's clock gone wrong, or a corrupted delivery: a price that claims to be from the future
    # is no evidence of what the instrument is worth now.
    ahead = Quote(VOO, Money.of("250.00", Currency.USD), NOON + timedelta(days=365))

    with pytest.raises(StaleQuoteError, match="ahead of"):
        LatestQuote(FakeFeed(ahead)).of(VOO, NOON)


def Given_a_quote_stamped_just_after_the_clock_was_read_When_asked_for_Then_it_is_returned() -> (
    None
):
    # The development seed stamps as_of after MarketDataQuotes has read the clock, so a quote a
    # few microseconds ahead is the normal case, not an error.
    just_after = Quote(VOO, Money.of("250.00", Currency.USD), NOON + timedelta(microseconds=5))

    assert LatestQuote(FakeFeed(just_after)).of(VOO, NOON) is just_after
