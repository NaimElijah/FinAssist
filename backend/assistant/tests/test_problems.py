"""Every failure the assistant answers is an RFC 9457 problem, and a foreign Host is refused.

The web client calls the ledger and the assistant alike (ADR-026) and parses one error shape from
both (ADR-028). These tests hold the assistant to the ledger's half of that bargain: the same
members, the same ``code`` strings where the meanings overlap, and no exception text on a 5xx.
They also hold the log line a 5xx leaves to the rule that the owner's money is never logged.

The routes that raise are added here, not served by the app. They exercise the mapping for every
exception the table names without depending on which endpoint happens to raise which today.

The Host tests hold ADR-027's defence against DNS rebinding, refused in the ledger's shape.
"""

from __future__ import annotations

import json
from collections.abc import Iterator, Mapping
from decimal import Decimal
from typing import Annotated, Any, Protocol

import pytest
import structlog
from fastapi import APIRouter, FastAPI, Query
from fastapi.testclient import TestClient
from pydantic import BaseModel, ValidationError
from starlette.exceptions import HTTPException
from starlette.types import ASGIApp, Receive, Scope, Send

from finassist_assistant.adapters.app import CORRELATION_ID_HEADER, create_app
from finassist_assistant.adapters.observability import forget_secrets, reset_logging
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.portfolio.adapters.inbound.api.models import MoneyModel
from finassist_assistant.portfolio.application.port import (
    HoldingNotFoundError,
    PriceUnavailableError,
)
from finassist_assistant.portfolio.domain.analysis import InsufficientHoldingError
from finassist_assistant.portfolio.domain.model import Symbol
from finassist_assistant.shared import Currency, CurrencyMismatchError

PROBLEM_JSON = "application/problem+json"

# Stands in for whatever an exception might carry: a path, a query, a price, a key. It may reach a
# log line, which is redacted at the boundary; it must never reach a response body.
LEAK = "internal detail that must stay off the wire"

# A recognisable amount, and an input no wire model accepts that carries it. The routes refer to it
# by name, so the source line a traceback quotes cannot hold the digits either.
AMOUNT = "98765.43"
UNPARSEABLE_AMOUNT = f"{AMOUNT} USD"

VOO = Symbol("VOO")


class _Answer(Protocol):
    """What the helpers read from a response.

    Structural, because Starlette's test client returns the response type of the httpx fork it
    wraps, which is a transitive dependency no test should name.
    """

    @property
    def headers(self) -> Mapping[str, str]: ...

    def json(self) -> Any: ...


class _Inner(BaseModel):
    value: int


class _Outer(BaseModel):
    inner: _Inner


class _Count(BaseModel):
    count: int


def _failing_routes() -> APIRouter:
    router = APIRouter(prefix="/boom")

    @router.get("/holding-not-found")
    def holding_not_found() -> None:
        raise HoldingNotFoundError(VOO)

    @router.get("/price-unavailable")
    def price_unavailable() -> None:
        raise PriceUnavailableError(VOO)

    @router.get("/insufficient-holding")
    def insufficient_holding() -> None:
        raise InsufficientHoldingError(held=Decimal("1"), requested=Decimal("2"))

    @router.get("/currency-mismatch")
    def currency_mismatch() -> None:
        raise CurrencyMismatchError(Currency.ILS, Currency.USD)

    @router.get("/rule-violated")
    def rule_violated() -> None:
        raise ValueError("a rule was broken")

    @router.get("/invalid-model")
    def invalid_model() -> None:
        _Count.model_validate({"count": LEAK})

    @router.get("/wire-model-rejects-an-amount")
    def wire_model_rejects_an_amount() -> None:
        MoneyModel.model_validate({"amount": UNPARSEABLE_AMOUNT, "currency": "USD"})

    @router.get("/response-model-rejects-an-amount", response_model=MoneyModel)
    def response_model_rejects_an_amount() -> dict[str, str]:
        return {"amount": UNPARSEABLE_AMOUNT, "currency": "USD"}

    @router.get("/unexpected")
    def unexpected() -> None:
        raise RuntimeError(LEAK)

    @router.get("/bad-gateway")
    def bad_gateway() -> None:
        raise HTTPException(status_code=502, detail=LEAK)

    @router.get("/framework-bad-request")
    def framework_bad_request() -> None:
        raise HTTPException(status_code=400)

    @router.get("/malformed-query")
    def malformed_query(n: Annotated[str, Query(pattern=r"^\d+$")]) -> str:
        return n

    @router.post("/malformed-body")
    def malformed_body(payload: _Outer) -> int:
        return payload.inner.value

    return router


class _FailingOutsideCorrelation:
    """Raises before the correlation middleware runs, where only the last-resort handler answers."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            raise RuntimeError(LEAK)
        await self.app(scope, receive, send)


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
def app(settings: Settings) -> FastAPI:
    built = create_app(settings)
    built.include_router(_failing_routes())
    return built


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as running:
        yield running


def _problem(response: _Answer) -> dict[str, Any]:
    assert response.headers["content-type"] == PROBLEM_JSON
    body: dict[str, Any] = response.json()
    return body


def _records(captured: str, level: str) -> list[dict[str, Any]]:
    lines = (json.loads(line) for line in captured.strip().splitlines() if line.startswith("{"))
    return [record for record in lines if record.get("level") == level]


# --- the exceptions the assistant raises ---------------------------------------------------


@pytest.mark.parametrize(
    ("path", "status", "title", "code"),
    [
        ("/boom/holding-not-found", 404, "Not Found", "HOLDING_NOT_FOUND"),
        ("/boom/insufficient-holding", 422, "Unprocessable Content", "INSUFFICIENT_HOLDING"),
        ("/boom/currency-mismatch", 422, "Unprocessable Content", "CURRENCY_MISMATCH"),
        ("/boom/rule-violated", 422, "Unprocessable Content", "RULE_VIOLATED"),
    ],
)
def Given_a_refusal_When_it_is_answered_Then_it_is_a_problem_with_its_code(
    client: TestClient, path: str, status: int, title: str, code: str
) -> None:
    response = client.get(path)

    problem = _problem(response)
    assert response.status_code == status
    assert problem["type"] == "about:blank"
    assert problem["title"] == title
    assert problem["status"] == status
    assert problem["code"] == code
    assert problem["instance"] == path
    assert problem["detail"]


def Given_a_rule_violation_When_it_is_answered_Then_the_detail_is_the_message(
    client: TestClient,
) -> None:
    """A 4xx detail says what the caller got wrong, and the domain's message is that sentence."""
    assert _problem(client.get("/boom/rule-violated"))["detail"] == "a rule was broken"


def Given_a_currency_mismatch_When_it_is_answered_Then_it_is_not_a_plain_rule_violation(
    client: TestClient,
) -> None:
    """CurrencyMismatchError is a ValueError. Its own code must win over the generic one."""
    assert _problem(client.get("/boom/currency-mismatch"))["code"] == "CURRENCY_MISMATCH"


def Given_no_usable_price_When_it_is_answered_Then_it_is_503_without_the_message(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    capsys.readouterr()

    response = client.get("/boom/price-unavailable")

    problem = _problem(response)
    assert response.status_code == 503
    assert problem["title"] == "Service Unavailable"
    assert problem["code"] == "PRICE_UNAVAILABLE"
    assert str(VOO) not in response.text
    warnings = _records(capsys.readouterr().out, "warning")
    assert [record["correlation_id"] for record in warnings] == [
        response.headers[CORRELATION_ID_HEADER]
    ]
    # One line per 503 for as long as a feed is stale: the event and the symbol, never a traceback.
    assert warnings[0]["symbol"] == str(VOO)
    assert "exception" not in warnings[0]


# --- failures that are the assistant's own fault -------------------------------------------


def Given_a_model_that_fails_to_validate_When_it_is_answered_Then_it_is_500_not_422(
    client: TestClient,
) -> None:
    """pydantic's ValidationError is a ValueError. A model the assistant built is its own bug."""
    response = client.get("/boom/invalid-model")

    problem = _problem(response)
    assert response.status_code == 500
    assert problem["code"] == "INTERNAL_ERROR"
    assert LEAK not in response.text


@pytest.mark.parametrize(
    ("path", "location"),
    [
        ("/boom/wire-model-rejects-an-amount", "amount"),
        ("/boom/response-model-rejects-an-amount", "response.amount"),
    ],
    ids=["built-by-hand", "response-model"],
)
def Given_a_model_rejecting_an_amount_When_the_500_is_logged_Then_the_amount_is_not_in_the_log(
    client: TestClient, capsys: pytest.CaptureFixture[str], path: str, location: str
) -> None:
    """A validation error's text quotes the input it rejected, which here is the owner's money.

    Money is never logged, so the line names where validation failed and why, and not what it saw.
    The second case is FastAPI's own response check, whose error text quotes the input however the
    model is configured.
    """
    capsys.readouterr()

    response = client.get(path)

    captured = capsys.readouterr().out
    errors = _records(captured, "error")
    assert response.status_code == 500
    assert AMOUNT not in captured
    assert [record["correlation_id"] for record in errors] == [
        response.headers[CORRELATION_ID_HEADER]
    ]
    assert errors[0]["errors"] == [{"loc": location, "type": "string_pattern_mismatch"}]


def Given_an_amount_a_wire_model_rejects_When_its_error_is_printed_Then_it_is_hidden() -> None:
    """Anything that prints the error - a chained traceback, a debugger, a library's own log -
    would otherwise print the owner's money with it."""
    with pytest.raises(ValidationError) as rejected:
        MoneyModel.model_validate({"amount": UNPARSEABLE_AMOUNT, "currency": "USD"})

    assert AMOUNT not in str(rejected.value)
    assert AMOUNT not in repr(rejected.value)


def Given_an_unexpected_exception_When_it_is_answered_Then_nothing_of_it_reaches_the_wire(
    client: TestClient,
) -> None:
    response = client.get("/boom/unexpected")

    problem = _problem(response)
    assert response.status_code == 500
    assert problem["title"] == "Internal Server Error"
    assert problem["code"] == "INTERNAL_ERROR"
    assert LEAK not in response.text


def Given_an_unexpected_exception_When_it_is_logged_Then_the_line_carries_the_returned_id(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """The response withholds the cause, so the id it carries must find the log line that has it."""
    capsys.readouterr()

    response = client.get("/boom/unexpected")

    errors = _records(capsys.readouterr().out, "error")
    assert len(errors) == 1
    assert errors[0]["correlation_id"] == response.headers[CORRELATION_ID_HEADER]
    assert "RuntimeError" in errors[0]["exception"]


def Given_a_server_error_status_When_it_is_raised_Then_its_detail_is_withheld(
    client: TestClient,
) -> None:
    response = client.get("/boom/bad-gateway")

    problem = _problem(response)
    assert response.status_code == 502
    assert problem["code"] == "INTERNAL_ERROR"
    assert LEAK not in response.text


def Given_a_failure_outside_the_correlation_middleware_When_answered_Then_it_is_still_a_problem(
    app: FastAPI,
) -> None:
    """The last resort. Starlette's own answer here is plain text, which no client could parse."""
    app.add_middleware(_FailingOutsideCorrelation)

    with TestClient(app, raise_server_exceptions=False) as running:
        response = running.get("/health")

    problem = _problem(response)
    assert response.status_code == 500
    assert problem["code"] == "INTERNAL_ERROR"
    assert LEAK not in response.text


# --- malformed requests and the framework's own failures ------------------------------------


def Given_a_query_parameter_that_breaks_its_pattern_When_answered_Then_errors_name_it(
    client: TestClient,
) -> None:
    response = client.get("/boom/malformed-query", params={"n": "twelve"})

    problem = _problem(response)
    assert response.status_code == 400
    assert problem["title"] == "Bad Request"
    assert problem["code"] == "MALFORMED_REQUEST"
    assert [error["field"] for error in problem["errors"]] == ["n"]
    assert all(error["message"] for error in problem["errors"])


def Given_a_nested_body_field_of_the_wrong_type_When_answered_Then_its_field_is_dotted(
    client: TestClient,
) -> None:
    response = client.post("/boom/malformed-body", json={"inner": {"value": "many"}})

    problem = _problem(response)
    assert response.status_code == 400
    assert [error["field"] for error in problem["errors"]] == ["inner.value"]


def Given_a_framework_bad_request_When_answered_Then_it_is_coded_as_a_malformed_request(
    client: TestClient,
) -> None:
    """Starlette's 400 is named as the ledger names Spring's, not by its reason phrase."""
    response = client.get("/boom/framework-bad-request")

    problem = _problem(response)
    assert response.status_code == 400
    assert problem["code"] == "MALFORMED_REQUEST"


def Given_an_unknown_path_When_requested_Then_it_is_a_404_problem(client: TestClient) -> None:
    response = client.get("/nowhere")

    problem = _problem(response)
    assert response.status_code == 404
    assert problem["code"] == "NOT_FOUND"
    assert problem["instance"] == "/nowhere"


def Given_a_route_that_does_not_take_the_method_When_requested_Then_it_is_a_405_problem(
    client: TestClient,
) -> None:
    response = client.post("/health")

    problem = _problem(response)
    assert response.status_code == 405
    assert problem["code"] == "METHOD_NOT_ALLOWED"
    assert response.headers["allow"] == "GET"


@pytest.mark.parametrize("path", ["/docs", "/redoc"])
def Given_the_interactive_docs_When_requested_Then_they_are_a_404_problem(
    client: TestClient, path: str
) -> None:
    """Their pages load a UI from a CDN, and the ledger serves no such UI either (ADR-028)."""
    response = client.get(path)

    problem = _problem(response)
    assert response.status_code == 404
    assert problem["code"] == "NOT_FOUND"


def Given_the_openapi_document_When_requested_Then_it_is_still_served(client: TestClient) -> None:
    """The document is what a client is generated from; only the UI over it goes."""
    response = client.get("/openapi.json")

    assert response.status_code == 200
    assert response.json()["info"]["version"] == "v1"


# --- the Host allow-list --------------------------------------------------------------------


def Given_a_foreign_host_When_a_request_arrives_Then_it_is_refused_as_the_ledger_refuses_it(
    client: TestClient,
) -> None:
    """A DNS-rebound page still names its own host, and must not read what the assistant serves."""
    response = client.get("/health", headers={"host": "attacker.example"})

    problem = _problem(response)
    assert response.status_code == 403
    assert problem == {
        "type": "about:blank",
        "title": "Forbidden",
        "status": 403,
        "detail": problem["detail"],
        "code": "HOST_NOT_ALLOWED",
    }
    assert "attacker.example" not in response.text


@pytest.mark.parametrize("host", ["testserver", "testserver:8000"])
def Given_an_allowed_host_When_a_request_arrives_Then_it_is_served(
    client: TestClient, host: str
) -> None:
    assert client.get("/health", headers={"host": host}).status_code == 200


def Given_no_allowed_hosts_variable_When_settings_are_built_Then_only_loopback_names_are_allowed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("ASSISTANT_ALLOWED_HOSTS", raising=False)

    assert Settings().allowed_hosts == ["localhost", "127.0.0.1"]


def Given_a_comma_separated_variable_When_settings_are_built_Then_each_name_is_allowed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The ledger's LEDGER_ALLOWED_HOSTS is comma-separated; one format for both services."""
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", " LocalHost , 127.0.0.1,, finassist.test ")

    assert Settings().allowed_hosts == ["localhost", "127.0.0.1", "finassist.test"]


@pytest.mark.parametrize(
    "value", ["", " , ", "*", "*.example.com"], ids=["empty", "blank", "any", "suffix"]
)
def Given_an_allow_list_that_admits_everyone_or_no_one_When_settings_are_built_Then_it_is_refused(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    """An empty list refuses every request, like an outage; a wildcard is no list at all."""
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", value)

    with pytest.raises(ValidationError, match="ASSISTANT_ALLOWED_HOSTS"):
        Settings()


@pytest.mark.parametrize(
    "value",
    ["localhost:8000", "127.0.0.1:8000", "[::1]", "::1"],
    ids=["name-with-port", "address-with-port", "bracketed-ipv6", "bare-ipv6"],
)
def Given_an_allowed_host_with_a_port_or_bracket_When_settings_are_built_Then_it_is_refused(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    """The check compares only the Host header's text before its first colon, so such an entry
    matches no request. Listed alone it would refuse every one, as an empty list would."""
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", f"localhost,{value}")

    with pytest.raises(ValidationError, match="ASSISTANT_ALLOWED_HOSTS"):
        Settings()
