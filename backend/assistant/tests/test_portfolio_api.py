"""The portfolio endpoints, called over HTTP against a seeded assembly.

The web client's portfolio screens read these (ADR-026). The use cases behind them are tested on
their own; what is pinned here is the edge. Decimals travel as strings at the currency's scale,
members are camelCase as the ledger's are, each refusal is the problem the ledger would send for
the same meaning (ADR-028), and an amount of money never travels in a URL.
"""

from __future__ import annotations

import inspect
import json
import logging
from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any, get_args

import pytest
from fastapi import FastAPI
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient
from starlette.types import ASGIApp, Receive, Scope, Send
from uvicorn.protocols.utils import get_client_addr, get_path_with_query_string

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.auth import RequirePermission, TokenVerifier
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble
from finassist_assistant.marketdata.adapters.outbound.feed import InMemoryQuoteFeed
from finassist_assistant.portfolio.adapters.inbound.api.models import CurrencyCode, currency_for
from finassist_assistant.portfolio.adapters.inbound.api.router import portfolio_router
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

HOLDINGS = "/api/v1/portfolio/holdings"
DISPOSAL_URL = f"{HOLDINGS}/VOO/disposal"
PROBLEM_JSON = "application/problem+json"

NOON = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)
VOO = Symbol("VOO")
OLDER = Lot(VOO, Decimal("10"), Money.of("100.00", Currency.USD), date(2026, 1, 10))
NEWER = Lot(VOO, Decimal("5"), Money.of("120.00", Currency.USD), date(2026, 2, 10))

# Recognisable in any log output, and in no fixture above.
RECOGNISABLE_AMOUNT = "86420.13"


def _disposal(
    quantity: str = "12", amount: str = "3000.00", currency: str = "USD"
) -> dict[str, Any]:
    return {"quantity": quantity, "proceeds": {"amount": amount, "currency": currency}}


def _seam(assembly: Assembly) -> InMemoryQuoteFeed:
    """Narrows ``Assembly.feed`` back to the development seam's own API.

    The ``assembly`` fixture below builds with ``assemble()``'s default feed, so the widened
    ``QuoteFeedPort`` type is always an ``InMemoryQuoteFeed`` here — checked, never cast.
    """
    feed = assembly.feed
    assert isinstance(feed, InMemoryQuoteFeed)
    return feed


class _ScopeRecorder:
    """Keeps every HTTP scope the app receives.

    The scope is what OpenTelemetry takes a request's URL attributes from, and what uvicorn writes
    its access line from wherever that line is on; the assistant's own entry point turns it off, but
    another launcher need not. An amount absent from the scope reaches neither. Kept as ``Any``
    because uvicorn types its helpers with its own scope dictionaries, not Starlette's mapping.
    """

    def __init__(self, app: ASGIApp, scopes: list[Any]) -> None:
        self.app = app
        self.scopes = scopes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            self.scopes.append(scope)
        await self.app(scope, receive, send)


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
def app(settings: Settings, assembly: Assembly, token_verifier: TokenVerifier) -> FastAPI:
    return create_app(settings, assembly=assembly, token_verifier=token_verifier)


@pytest.fixture
def client(app: FastAPI, owner_headers: dict[str, str]) -> Iterator[TestClient]:
    with TestClient(app, headers=owner_headers) as running:
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
    _seam(assembly).publish("voo", "250", Currency.USD, NOON - timedelta(minutes=5))

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
    _seam(assembly).publish("voo", "250.00", Currency.USD, NOON - timedelta(hours=1, minutes=1))

    response = client.get(f"{HOLDINGS}/VOO/valuation")

    assert response.status_code == 503
    assert response.json()["code"] == "PRICE_UNAVAILABLE"


# --- pricing a disposal ---------------------------------------------------------------------


def Given_two_lots_When_a_disposal_is_priced_Then_the_oldest_is_consumed_first(
    client: TestClient,
) -> None:
    """200, not 201: pricing creates nothing."""
    response = client.post(DISPOSAL_URL, json=_disposal())

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
    """A POST here only so the proceeds stay out of the URL; like a GET, it is safe to repeat."""
    client.post(DISPOSAL_URL, json=_disposal())

    assert client.get(f"{HOLDINGS}/VOO/position").json()["quantity"] == "15"


def Given_a_disposal_When_serialised_Then_every_member_is_camel_case(client: TestClient) -> None:
    names = _member_names(client.post(DISPOSAL_URL, json=_disposal()).json())

    assert {"costOfSale", "realisedGain", "unitCost", "acquiredOn"} <= names
    assert not [name for name in names if "_" in name]


def Given_a_json_body_with_a_charset_When_a_disposal_is_priced_Then_it_is_accepted(
    client: TestClient,
) -> None:
    response = client.post(
        DISPOSAL_URL,
        content=json.dumps(_disposal()),
        headers={"content-type": "application/json; charset=utf-8"},
    )

    assert response.status_code == 200


def Given_more_units_than_are_held_When_a_disposal_is_priced_Then_it_is_insufficient_holding(
    client: TestClient,
) -> None:
    response = client.post(DISPOSAL_URL, json=_disposal(quantity="16"))

    assert response.status_code == 422
    assert response.json()["code"] == "INSUFFICIENT_HOLDING"


@pytest.mark.parametrize("quantity", ["0", "-1"])
def Given_a_non_positive_quantity_When_a_disposal_is_priced_Then_the_rule_is_violated(
    client: TestClient, quantity: str
) -> None:
    response = client.post(DISPOSAL_URL, json=_disposal(quantity=quantity))

    assert response.status_code == 422
    assert response.json()["code"] == "RULE_VIOLATED"


def Given_a_quantity_finer_than_28_digits_When_a_disposal_is_priced_Then_nothing_is_rounded(
    client: TestClient,
) -> None:
    """The wire bounds neither the digits nor the scale of a quantity, and exact decimal arithmetic
    is the domain's promise. Rounded, this sale left the older lot at ten while the response echoed
    the quantity as sold."""
    ten_to_the_minus_32 = "0." + "0" * 31 + "1"

    response = client.post(DISPOSAL_URL, json=_disposal(quantity=ten_to_the_minus_32))

    assert response.status_code == 200
    assert response.json()["quantity"] == ten_to_the_minus_32
    assert [lot["quantity"] for lot in response.json()["remaining"]] == ["9." + "9" * 32, "5"]


def Given_proceeds_finer_than_the_currency_admits_When_priced_Then_they_are_refused_not_rounded(
    client: TestClient,
) -> None:
    response = client.post(DISPOSAL_URL, json=_disposal(amount="3000.005"))

    assert response.status_code == 422
    assert response.json()["code"] == "RULE_VIOLATED"


@pytest.mark.parametrize(
    ("field", "body"),
    [
        ("quantity", _disposal(quantity="twelve")),
        ("quantity", _disposal(quantity="1e3")),
        ("quantity", _disposal(quantity="12abc")),
        ("proceeds.amount", _disposal(amount="3,000.00")),
        ("proceeds.currency", _disposal(currency="CHF")),
    ],
    ids=["prose", "exponent", "trailing-text", "grouped-digits", "unknown-currency"],
)
def Given_a_malformed_body_member_When_a_disposal_is_priced_Then_it_is_a_malformed_request(
    client: TestClient, field: str, body: dict[str, Any]
) -> None:
    """The pattern is anchored: JSON Schema's is not, so "12abc" would pass an unanchored one."""
    response = client.post(DISPOSAL_URL, json=body)

    assert response.status_code == 400
    assert response.json()["code"] == "MALFORMED_REQUEST"
    assert [error["field"] for error in response.json()["errors"]] == [field]


@pytest.mark.parametrize(
    ("field", "body"),
    [
        # ARABIC-INDIC DIGIT ONE and TWO: twelve and a half, to Python's Decimal.
        ("proceeds.amount", _disposal(amount="\u0661\u0662.50")),
        # FULLWIDTH DIGIT ONE and TWO: twelve, to Python's Decimal.
        ("quantity", _disposal(quantity="\uff11\uff12")),
    ],
    ids=["arabic-indic-amount", "fullwidth-quantity"],
)
def Given_a_decimal_in_non_ascii_digits_When_a_disposal_is_priced_Then_it_is_a_malformed_request(
    client: TestClient, field: str, body: dict[str, Any]
) -> None:
    """The published pattern's ``\\d`` is an ASCII digit to the ledger's Java and to JSON Schema,
    so the ledger answers these 400. pydantic's regex engine reads it as any Unicode digit, and the
    same pattern text must not mean more on this wire than on the ledger's."""
    refused_by_pattern = client.post(DISPOSAL_URL, json=_disposal(quantity="12abc")).json()

    response = client.post(DISPOSAL_URL, json=body)

    assert response.status_code == 400
    assert response.json()["code"] == "MALFORMED_REQUEST"
    # The same refusal, in the same words, as any other text the pattern refuses.
    assert response.json()["errors"] == [
        {"field": field, "message": refused_by_pattern["errors"][0]["message"]}
    ]


@pytest.mark.parametrize(
    "sent",
    [
        {"data": {"quantity": "12"}},
        {"content": json.dumps(_disposal()), "headers": {"content-type": "text/plain"}},
        {"content": json.dumps(_disposal())},
    ],
    ids=["form-encoded", "text-plain", "no-content-type"],
)
def Given_a_body_that_is_not_json_When_a_disposal_is_priced_Then_it_is_unsupported_media_type(
    client: TestClient, sent: dict[str, Any]
) -> None:
    """JSON only, as every ledger POST is (ADR-030). A cross-site form cannot send JSON without a
    preflight, and no CORS configuration exists to approve one."""
    response = client.post(DISPOSAL_URL, **sent)

    assert response.status_code == 415
    assert response.headers["content-type"] == PROBLEM_JSON
    assert response.json()["code"] == "UNSUPPORTED_MEDIA_TYPE"


def Given_a_disposal_priced_by_get_with_query_parameters_When_requested_Then_it_is_not_allowed(
    client: TestClient,
) -> None:
    """The former shape, which put the proceeds in the URL, is gone rather than kept alongside."""
    response = client.get(DISPOSAL_URL, params={"quantity": "12", "proceeds": "3000.00"})

    assert response.status_code == 405
    assert response.json()["code"] == "METHOD_NOT_ALLOWED"
    assert response.headers["allow"] == "POST"


def Given_proceeds_with_a_recognisable_amount_When_a_disposal_is_priced_Then_no_log_line_holds_it(
    app: FastAPI, capsys: pytest.CaptureFixture[str], owner_headers: dict[str, str]
) -> None:
    """Money never travels in a URL. A URL is written to trace attributes, by any proxy on the way
    and by any server access log left on, and keeping the amount out of the URL keeps it out of all
    of them at once.

    The assistant's entry point switches uvicorn's access log off, but a different launcher would
    leave it on. TestClient runs no server, so the line uvicorn writes when it is on is written
    here, from the scope the app actually received, with uvicorn's own helpers and format. The test
    client's own request line and every line the service writes are captured with it.
    """
    scopes: list[Any] = []
    app.add_middleware(_ScopeRecorder, scopes=scopes)

    with TestClient(app, headers=owner_headers) as running:
        capsys.readouterr()
        response = running.post(DISPOSAL_URL, json=_disposal(amount=RECOGNISABLE_AMOUNT))
        received = scopes[-1]
        logging.getLogger("uvicorn.access").info(
            '%s - "%s %s HTTP/%s" %d',
            get_client_addr(received),
            received["method"],
            get_path_with_query_string(received),
            received["http_version"],
            response.status_code,
        )

    captured = capsys.readouterr().out
    assert response.json()["proceeds"]["amount"] == RECOGNISABLE_AMOUNT
    # The access line was written: the JSON renderer escapes the quotes around its request line, so
    # match inside them. The client's own line names the full URL, so this matches only uvicorn's.
    assert f"POST {DISPOSAL_URL} HTTP/1.1" in captured
    assert RECOGNISABLE_AMOUNT not in captured


# --- refusals every endpoint shares ---------------------------------------------------------


@pytest.mark.parametrize(
    ("method", "path", "body"),
    [
        ("GET", "VWCE/position", None),
        ("GET", "VWCE/valuation", None),
        ("POST", "VWCE/disposal", _disposal(quantity="1", amount="1.00")),
    ],
    ids=["position", "valuation", "disposal"],
)
def Given_nothing_held_for_the_symbol_When_requested_Then_it_is_holding_not_found(
    client: TestClient, method: str, path: str, body: dict[str, Any] | None
) -> None:
    response = client.request(method, f"{HOLDINGS}/{path}", json=body)

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
        portfolio.view_position,
        portfolio.value_holding,
        portfolio.dispose_units,
        require=RequirePermission,
    )
    routes = [route for route in router.routes if isinstance(route, APIRoute)]

    assert len(routes) == 3
    assert not [route.name for route in routes if inspect.iscoroutinefunction(route.endpoint)]


def Given_the_disposal_operation_When_documented_Then_it_says_it_records_nothing(
    app: FastAPI,
) -> None:
    operation = app.openapi()["paths"][f"{HOLDINGS}/{{symbol}}/disposal"]["post"]

    assert "records nothing" in operation["description"]


def Given_the_probes_When_the_schema_is_generated_Then_they_are_not_part_of_it(
    app: FastAPI,
) -> None:
    """Probes are for the platform, not for a client generated from the contract (ADR-028)."""
    paths = app.openapi()["paths"]

    assert "/health" not in paths
    assert "/ready" not in paths


# --- the currencies the wire carries --------------------------------------------------------


def Given_the_wire_currency_codes_When_compared_Then_they_are_exactly_the_domain_currencies() -> (
    None
):
    """A currency added to the domain but not to the wire would fail as a 500 on its first use."""
    assert set(get_args(CurrencyCode)) == {currency.code for currency in Currency}


@pytest.mark.parametrize("code", get_args(CurrencyCode))
def Given_a_wire_currency_code_When_resolved_Then_it_is_the_currency_with_that_code(
    code: CurrencyCode,
) -> None:
    """Resolved by code, the value the ``Literal`` lists and the parity test compares, and never by
    member name. A member whose name differed from its code would otherwise pass the parity test and
    still be a ``KeyError``, answered 500, on the first request that named it."""
    assert currency_for(code).code == code


@pytest.mark.parametrize("code", get_args(CurrencyCode))
def Given_proceeds_in_any_wire_currency_When_a_disposal_is_priced_Then_the_code_is_understood(
    client: TestClient, code: str
) -> None:
    """Proceeds in the lots' currency realise a gain; in any other they cannot be combined with the
    cost. Either way the code was understood: a refusal about the request, never a 500."""
    expected = 200 if code == OLDER.unit_cost.currency.code else 422

    response = client.post(DISPOSAL_URL, json=_disposal(amount="3000", currency=code))

    assert response.status_code == expected
