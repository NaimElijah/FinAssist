"""The development seed: fictional holdings and quotes, opt-in behind ``ASSISTANT_DEV_SEED``.

Off by default, so an ordinary run serves nothing and every portfolio endpoint answers
``HOLDING_NOT_FOUND`` exactly as it does today. On, it gives the portfolio screens something to
show by hand while there is no real ledger client or price feed. The seeded quote source is the
point of the exercise: it must not go stale merely because the process has been running a while.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.seed import seeded_assembly
from finassist_assistant.adapters.settings import Settings

HOLDINGS = "/api/v1/portfolio/holdings"
START = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)

# Present in the seed data; asserted absent from the start-up log.
_SEEDED_AMOUNT = "380.00"


class _MovableClock:
    """A clock a test can advance, standing in for the wall clock across a simulated time skip."""

    def __init__(self, start: datetime) -> None:
        self._now = start

    def __call__(self) -> datetime:
        return self._now

    def advance(self, delta: timedelta) -> None:
        self._now += delta


def _settings(*, dev_seed: bool) -> Settings:
    return Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`.
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
        ASSISTANT_DEV_SEED=dev_seed,
    )


def Given_the_environment_names_no_seed_setting_When_read_Then_it_defaults_to_off() -> None:
    assert Settings(ASSISTANT_ALLOWED_HOSTS=["testserver"]).dev_seed is False


def Given_the_seed_is_off_When_a_seeded_symbol_is_read_Then_it_is_holding_not_found() -> None:
    with TestClient(create_app(_settings(dev_seed=False))) as client:
        response = client.get(f"{HOLDINGS}/VOO/position")

    assert response.status_code == 404
    assert response.json()["code"] == "HOLDING_NOT_FOUND"


@pytest.mark.parametrize(
    ("symbol", "quantity", "currency"),
    [("VOO", "15", "USD"), ("DEMO", "100", "ILS")],
    ids=["two-usd-lots", "one-ils-lot"],
)
def Given_the_seed_is_on_When_a_seeded_position_is_read_Then_it_is_the_seeded_total(
    symbol: str, quantity: str, currency: str
) -> None:
    with TestClient(create_app(_settings(dev_seed=True))) as client:
        response = client.get(f"{HOLDINGS}/{symbol}/position")

    assert response.status_code == 200
    assert response.json()["quantity"] == quantity
    assert response.json()["cost"]["currency"] == currency


@pytest.mark.parametrize(("symbol", "currency"), [("VOO", "USD"), ("DEMO", "ILS")])
def Given_the_seed_is_on_When_a_seeded_holding_is_valued_Then_it_is_priced_in_its_own_currency(
    symbol: str, currency: str
) -> None:
    """Each seeded price must be in its lots' currency. A price typed in another would answer this
    valuation 422 ``CURRENCY_MISMATCH``, because no amount is ever converted (ADR-012)."""
    app = create_app(_settings(dev_seed=True), assembly=seeded_assembly(_MovableClock(START)))

    with TestClient(app) as client:
        response = client.get(f"{HOLDINGS}/{symbol}/valuation")

    assert response.status_code == 200
    amounts = ("cost", "unitPrice", "marketValue", "unrealisedGain")
    assert {response.json()[amount]["currency"] for amount in amounts} == {currency}


def Given_the_seed_is_on_and_two_hours_have_passed_When_valued_Then_the_price_is_still_fresh() -> (
    None
):
    """The stale-quote guard: LatestQuote refuses anything older than an hour, and a quote
    published once at start-up would fail it long before this. The seed's quote is stamped fresh
    on every read, so it never ages regardless of how long the process has been up."""
    clock = _MovableClock(START)
    app = create_app(_settings(dev_seed=True), assembly=seeded_assembly(clock))
    clock.advance(timedelta(hours=2))

    with TestClient(app) as client:
        response = client.get(f"{HOLDINGS}/VOO/valuation")

    assert response.status_code == 200


def Given_the_seed_is_on_When_an_unseeded_symbol_is_read_Then_it_is_holding_not_found() -> None:
    with TestClient(create_app(_settings(dev_seed=True))) as client:
        response = client.get(f"{HOLDINGS}/NOSUCH/position")

    assert response.status_code == 404
    assert response.json()["code"] == "HOLDING_NOT_FOUND"


def Given_the_seed_is_on_When_the_app_starts_Then_it_is_logged_with_symbols_and_no_amount(
    capsys: pytest.CaptureFixture[str],
) -> None:
    capsys.readouterr()
    create_app(_settings(dev_seed=True))
    captured = capsys.readouterr().out

    assert "development seed loaded" in captured
    assert "VOO" in captured
    assert _SEEDED_AMOUNT not in captured
