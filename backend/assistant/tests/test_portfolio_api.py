"""The portfolio endpoints, called over HTTP against a seeded assembly.

The web client's portfolio screens read these (ADR-026). The use cases behind them are tested on
their own; what is pinned here is the edge. Decimals travel as strings at the currency's scale,
members are camelCase as the ledger's are, and each refusal is the problem the ledger would send
for the same meaning (ADR-028).
"""

from __future__ import annotations

import inspect
from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import get_args

import pytest
import structlog
from fastapi import FastAPI
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.observability import forget_secrets, reset_logging
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble
from finassist_assistant.portfolio.adapters.inbound.api.models import CurrencyCode
from finassist_assistant.portfolio.adapters.inbound.api.router import portfolio_router
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

HOLDINGS = "/api/v1/portfolio/holdings"
PROBLEM_JSON = "application/problem+json"

NOON = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)
VOO = Symbol("VOO")
OLDER = Lot(VOO, Decimal("10"), Money.of("100.00", Currency.USD), date(2026, 1, 10))
NEWER = Lot(VOO, Decimal("5"), Money.of("120.00", Currency.USD), date(2026, 2, 10))

DISPOSAL = {"quantity": "12", "proceeds": "3000.00", "currency": "USD"}


@pytest.fixture(autouse=True)
def _reset_observability() -> Iterator[None]:
    """Registered secrets, structlog and the root logger are global; no test may leak any."""
    forget_secrets()
    yield
    forget_secrets()
    structlog.reset_defaults()
    reset_logging()


@pytest.fixture
def settings() -> Settings:
    return Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`.
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
    )


@pytest.fixture
def assembly() -> Assembly:
    built = assemble(clock=lambda: NOON)
    # Newest first, so an endpoint consuming lots in the order they arrived would price LIFO.
    built.ledger.add(NEWER)
    built.ledger.add(OLDER)
    return built


@pytest.fixture
def app(settings: Settings, assembly: Assembly) -> FastAPI:
    return create_app(settings, assembly=assembly)


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as running:
        yield running


def _member_names(value: object) -> set[str]:
    if isinstance(value, dict):
        nested = {name for item in value.values() for name in _member_names(item)}
        return {str(name) for name in value} | nested
    if isinstance(value, list):
        return {name for item in value for name in _member_names(item)}
    return set()


# --- reading a position -----------------------------------------------------------------------


def Given_lots_for_a_symbol_When_its_position_is_read_Then_quantity_and_cost_are_totalled(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/voo/position")

    assert response.status_code == 200
    assert response.json() == {
        "symbol": "VOO",
        "quantity": "15",
        "cost": {"amount": "1600.00", "currency": "USD"},
    }


def Given_a_holding_in_yen_When_its_position_is_read_Then_the_amount_has_no_minor_units(
    client: TestClient, assembly: Assembly
) -> None:
    """JPY has no minor units; "1200.00" would claim a precision the currency does not have."""
    assembly.ledger.add(
        Lot(Symbol("7203"), Decimal("1"), Money.of("1200", Currency.JPY), date(2026, 1, 5))
    )

    response = client.get(f"{HOLDINGS}/7203/position")

    assert response.json()["cost"] == {"amount": "1200", "currency": "JPY"}


# --- valuing a holding ----------------------------------------------------------------------


def Given_a_fresh_price_When_a_holding_is_valued_Then_amounts_are_strings_at_the_currency_scale(
    client: TestClient, assembly: Assembly
) -> None:
    assembly.feed.publish("voo", "250", Currency.USD, NOON - timedelta(minutes=5))

    response = client.get(f"{HOLDINGS}/VOO/valuation")

    assert response.status_code == 200
    assert response.json() == {
        "symbol": "VOO",
        "quantity": "15",
        "cost": {"amount": "1600.00", "currency": "USD"},
        "unitPrice": {"amount": "250.00", "currency": "USD"},
        "marketValue": {"amount": "3750.00", "currency": "USD"},
        "unrealisedGain": {"amount": "2150.00", "currency": "USD"},
    }


def Given_no_quote_When_a_holding_is_valued_Then_it_is_price_unavailable(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/VOO/valuation")

    assert response.status_code == 503
    assert response.headers["content-type"] == PROBLEM_JSON
    assert response.json()["code"] == "PRICE_UNAVAILABLE"


def Given_a_quote_older_than_an_hour_When_a_holding_is_valued_Then_it_is_price_unavailable(
    client: TestClient, assembly: Assembly
) -> None:
    """A stale price values a holding with a number that looks current and is not."""
    assembly.feed.publish("voo", "250.00", Currency.USD, NOON - timedelta(hours=1, minutes=1))

    response = client.get(f"{HOLDINGS}/VOO/valuation")

    assert response.status_code == 503
    assert response.json()["code"] == "PRICE_UNAVAILABLE"


# --- pricing a disposal ---------------------------------------------------------------------


def Given_two_lots_When_a_disposal_is_priced_Then_the_oldest_is_consumed_first(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/VOO/disposal", params=DISPOSAL)

    assert response.status_code == 200
    assert response.json() == {
        "symbol": "VOO",
        "quantity": "12",
        "proceeds": {"amount": "3000.00", "currency": "USD"},
        "costOfSale": {"amount": "1240.00", "currency": "USD"},
        "realisedGain": {"amount": "1760.00", "currency": "USD"},
        "remaining": [
            {
                "quantity": "3",
                "unitCost": {"amount": "120.00", "currency": "USD"},
                "acquiredOn": "2026-02-10",
            }
        ],
    }


def Given_a_priced_disposal_When_the_position_is_read_again_Then_nothing_was_consumed(
    client: TestClient,
) -> None:
    """A GET that records nothing is safe to repeat, which is why it is a GET (ADR-028)."""
    client.get(f"{HOLDINGS}/VOO/disposal", params=DISPOSAL)

    assert client.get(f"{HOLDINGS}/VOO/position").json()["quantity"] == "15"


def Given_a_disposal_When_serialised_Then_every_member_is_camel_case(client: TestClient) -> None:
    names = _member_names(client.get(f"{HOLDINGS}/VOO/disposal", params=DISPOSAL).json())

    assert {"costOfSale", "realisedGain", "unitCost", "acquiredOn"} <= names
    assert not [name for name in names if "_" in name]


def Given_more_units_than_are_held_When_a_disposal_is_priced_Then_it_is_insufficient_holding(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/VOO/disposal", params={**DISPOSAL, "quantity": "16"})

    assert response.status_code == 422
    assert response.json()["code"] == "INSUFFICIENT_HOLDING"


@pytest.mark.parametrize("quantity", ["0", "-1"])
def Given_a_non_positive_quantity_When_a_disposal_is_priced_Then_the_rule_is_violated(
    client: TestClient, quantity: str
) -> None:
    response = client.get(f"{HOLDINGS}/VOO/disposal", params={**DISPOSAL, "quantity": quantity})

    assert response.status_code == 422
    assert response.json()["code"] == "RULE_VIOLATED"


def Given_proceeds_finer_than_the_currency_admits_When_priced_Then_they_are_refused_not_rounded(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/VOO/disposal", params={**DISPOSAL, "proceeds": "3000.005"})

    assert response.status_code == 422
    assert response.json()["code"] == "RULE_VIOLATED"


@pytest.mark.parametrize(
    ("parameter", "value"),
    [
        ("quantity", "twelve"),
        ("quantity", "1e3"),
        ("quantity", "12abc"),
        ("proceeds", "3,000.00"),
        ("currency", "CHF"),
    ],
    ids=["prose", "exponent", "trailing-text", "grouped-digits", "unknown-currency"],
)
def Given_a_malformed_query_parameter_When_a_disposal_is_priced_Then_it_is_a_malformed_request(
    client: TestClient, parameter: str, value: str
) -> None:
    """The pattern is anchored: JSON Schema's is not, so "12abc" would pass an unanchored one."""
    response = client.get(f"{HOLDINGS}/VOO/disposal", params={**DISPOSAL, parameter: value})

    assert response.status_code == 400
    assert response.json()["code"] == "MALFORMED_REQUEST"
    assert [error["field"] for error in response.json()["errors"]] == [parameter]


# --- refusals every endpoint shares ---------------------------------------------------------


@pytest.mark.parametrize(
    "path",
    ["VWCE/position", "VWCE/valuation", "VWCE/disposal?quantity=1&proceeds=1.00&currency=USD"],
    ids=["position", "valuation", "disposal"],
)
def Given_nothing_held_for_the_symbol_When_requested_Then_it_is_holding_not_found(
    client: TestClient, path: str
) -> None:
    response = client.get(f"{HOLDINGS}/{path}")

    assert response.status_code == 404
    assert response.headers["content-type"] == PROBLEM_JSON
    assert response.json()["code"] == "HOLDING_NOT_FOUND"


def Given_a_blank_symbol_When_a_position_is_read_Then_the_rule_is_violated(
    client: TestClient,
) -> None:
    response = client.get(f"{HOLDINGS}/%20/position")

    assert response.status_code == 422
    assert response.json()["code"] == "RULE_VIOLATED"


# --- the shape of the routes themselves -----------------------------------------------------


def Given_the_portfolio_routes_When_inspected_Then_none_is_a_coroutine(assembly: Assembly) -> None:
    """The ports are synchronous (ADR-021). An async handler calling one would block the loop.

    Read from the router the context builds: an app holds an included router as one opaque route.
    """
    portfolio = assembly.portfolio
    router = portfolio_router(
        portfolio.view_position, portfolio.value_holding, portfolio.dispose_units
    )
    routes = [route for route in router.routes if isinstance(route, APIRoute)]

    assert len(routes) == 3
    assert not [route.name for route in routes if inspect.iscoroutinefunction(route.endpoint)]


def Given_the_disposal_operation_When_documented_Then_it_says_it_records_nothing(
    app: FastAPI,
) -> None:
    operation = app.openapi()["paths"][f"{HOLDINGS}/{{symbol}}/disposal"]["get"]

    assert "records nothing" in operation["description"]


def Given_the_probes_When_the_schema_is_generated_Then_they_are_not_part_of_it(
    app: FastAPI,
) -> None:
    """Probes are for the platform, not for a client generated from the contract (ADR-028)."""
    paths = app.openapi()["paths"]

    assert "/health" not in paths
    assert "/ready" not in paths


def Given_the_wire_currency_codes_When_compared_Then_they_are_exactly_the_domain_currencies() -> (
    None
):
    """A currency added to the domain but not to the wire would fail as a 500 on its first use."""
    assert set(get_args(CurrencyCode)) == {currency.code for currency in Currency}
