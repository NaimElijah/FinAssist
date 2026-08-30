"""Quotes, and staleness decided against a clock that is passed in, never read."""

from datetime import UTC, datetime, timedelta

import pytest

from finassist_assistant.marketdata.domain.model import Instrument, Quote, Ticker
from finassist_assistant.marketdata.domain.normalisation import age, is_stale, normalise
from finassist_assistant.shared import Currency, Money

NOON = datetime(2026, 3, 1, 12, 0, tzinfo=UTC)


def quote_at(moment: datetime) -> Quote:
    return Quote(Ticker("VWCE"), Money.of("120.00", Currency.USD), moment)


def Given_tickers_differing_in_case_When_compared_Then_they_are_one_ticker() -> None:
    assert Ticker("vwce") == Ticker("  VWCE ")


def Given_a_blank_ticker_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must not be blank"):
        Ticker("  ")


def Given_an_instrument_without_a_name_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must have a name"):
        Instrument(Ticker("VWCE"), "   ")


def Given_a_negative_price_When_a_quote_is_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="cannot be negative"):
        Quote(Ticker("VWCE"), Money.of("-1.00", Currency.USD), NOON)


def Given_a_naive_timestamp_When_a_quote_is_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="timezone"):
        Quote(Ticker("VWCE"), Money.of("120.00", Currency.USD), datetime(2026, 3, 1, 12, 0))


def Given_a_quote_older_than_the_limit_When_checked_Then_it_is_stale() -> None:
    assert is_stale(quote_at(NOON - timedelta(hours=2)), NOON, timedelta(hours=1))


def Given_a_quote_exactly_at_the_limit_When_checked_Then_it_is_not_yet_stale() -> None:
    assert not is_stale(quote_at(NOON - timedelta(hours=1)), NOON, timedelta(hours=1))


def Given_a_fresh_quote_When_checked_Then_it_is_not_stale() -> None:
    assert not is_stale(quote_at(NOON - timedelta(minutes=5)), NOON, timedelta(hours=1))


def Given_a_naive_now_When_staleness_is_checked_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="timezone"):
        is_stale(quote_at(NOON), datetime(2026, 3, 1, 12, 0), timedelta(hours=1))


def Given_a_non_positive_max_age_When_staleness_is_checked_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must be positive"):
        is_stale(quote_at(NOON), NOON, timedelta(0))


def Given_a_quote_When_its_age_is_taken_Then_it_is_the_elapsed_time() -> None:
    assert age(quote_at(NOON - timedelta(hours=3)), NOON) == timedelta(hours=3)


def Given_a_feeds_string_price_When_normalised_Then_it_becomes_an_exact_quote() -> None:
    quote = normalise("vwce", "120.5", Currency.USD, NOON)
    assert quote.ticker == Ticker("VWCE")
    assert quote.price == Money.of("120.50", Currency.USD)
    assert quote.as_of == NOON


def Given_a_price_that_is_not_a_number_When_normalised_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="not a decimal number"):
        normalise("VWCE", "n/a", Currency.USD, NOON)


def Given_a_price_too_precise_for_the_currency_When_normalised_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="more precision"):
        normalise("VWCE", "120.5051", Currency.USD, NOON)


def Given_a_blank_ticker_When_normalised_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must not be blank"):
        normalise("   ", "412.55", Currency.USD, NOON)


def Given_a_negative_price_When_normalised_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="cannot be negative"):
        normalise("VWCE", "-1.00", Currency.USD, NOON)


def Given_a_naive_timestamp_When_normalised_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="timezone"):
        normalise("VWCE", "412.55", Currency.USD, datetime(2026, 3, 1, 12, 0))


def Given_a_price_that_is_not_finite_When_normalised_Then_it_is_refused() -> None:
    # Decimal accepts "NaN" and "Infinity", so these slip past the InvalidOperation handler and
    # are caught by Money's own finiteness check instead - a different message, same refusal.
    with pytest.raises(ValueError, match="finite"):
        normalise("VWCE", "NaN", Currency.USD, NOON)


def Given_a_quote_dated_in_the_future_When_aged_Then_the_age_is_negative_and_it_is_not_stale() -> (
    None
):
    future = quote_at(NOON + timedelta(hours=1))

    assert age(future, NOON) == timedelta(hours=-1)
    assert is_stale(future, NOON, timedelta(hours=1)) is False


def Given_a_negative_maximum_age_When_staleness_is_judged_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must be positive"):
        is_stale(quote_at(NOON), NOON, timedelta(hours=-1))


def Given_an_instrument_name_padded_with_spaces_When_built_Then_it_is_stripped() -> None:
    assert (
        Instrument(Ticker("VWCE"), "  Vanguard FTSE All-World  ").name == "Vanguard FTSE All-World"
    )


def Given_a_quote_priced_at_zero_When_built_Then_it_is_allowed() -> None:
    assert Quote(Ticker("VWCE"), Money.of("0.00", Currency.USD), NOON).price.is_zero
