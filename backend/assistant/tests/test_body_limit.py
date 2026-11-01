"""A request body over the limit is refused 413 before it is buffered or parsed.

Without a bound, FastAPI reads and parses the whole body of a request before any dependency runs,
the permission check included, so one request could make the assistant allocate without limit.
``RequestBodyLimit`` refuses a declared ``Content-Length`` over the limit before reading anything,
and counts the bytes of a body that declares none, such as a chunked one.

``TestClient`` hands the app a whole body in one message, so the chunked tests re-deliver it in
small messages, as a server does, to show that counting stops at the message that crosses the
limit rather than after the whole body has arrived.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from finassist_assistant.adapters.app import CORRELATION_ID_HEADER, create_app
from finassist_assistant.adapters.auth import TokenVerifier
from finassist_assistant.adapters.body_limit import MAX_REQUEST_BODY_BYTES
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble
from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Currency, Money

DISPOSAL_URL = "/api/v1/portfolio/holdings/VOO/disposal"
PROBLEM_JSON = "application/problem+json"
CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE"

AGREED_BODY_LIMIT_BYTES = 64 * 1024
"""The limit every service enforces. The ledger and the gateway hold the same constant, and ADR-058
records the agreement; this suite cannot read theirs, so it pins this one to the agreed value."""

BODY_REFUSED_EVENT = "refused a request whose body is larger than the limit"
"""The ledger's ``RequestBodyLimitFilter`` warning, in this service's lower-case event style."""

CHUNK_BYTES = 1024
"""How much of a body each re-delivered message carries."""

NOON = datetime(2026, 3, 5, 12, 0, tzinfo=UTC)
HELD = Lot(Symbol("VOO"), Decimal("20"), Money.of("100.00", Currency.USD), date(2026, 1, 10))


@dataclass
class _Reads:
    """What the app behind the harness pulled from ``receive``."""

    messages: int = 0
    body_bytes: int = 0


@dataclass
class _ReadRecorder:
    """Wraps the app and records each body message it reads, optionally re-chunking the body.

    With *chunk* set, the first message's body is split into messages of that size with
    ``more_body`` set on all but the last, which is what a server delivers for a body that
    arrives over several reads.
    """

    app: ASGIApp
    chunk: int | None = None
    reads: _Reads = field(default_factory=_Reads)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        pending: list[Message] = []

        async def recorded() -> Message:
            if not pending:
                message = await receive()
                if message["type"] != "http.request" or self.chunk is None:
                    pending.append(message)
                else:
                    pending.extend(_split(message, self.chunk))
            message = pending.pop(0)
            if message["type"] == "http.request":
                self.reads.messages += 1
                self.reads.body_bytes += len(message.get("body", b""))
            return message

        await self.app(scope, recorded, send)


def _split(message: Message, size: int) -> list[Message]:
    body: bytes = message.get("body", b"")
    pieces = [body[start : start + size] for start in range(0, len(body), size)] or [b""]
    last = len(pieces) - 1
    return [
        {"type": "http.request", "body": piece, "more_body": index < last}
        for index, piece in enumerate(pieces)
    ]


def _disposal_of_exactly(size: int) -> bytes:
    """A valid disposal body, padded with trailing whitespace to exactly *size* bytes."""
    body = json.dumps(
        {"quantity": "12", "proceeds": {"amount": "3000.00", "currency": "USD"}}
    ).encode()
    return body + b" " * (size - len(body))


def _chunked(body: bytes) -> Iterator[bytes]:
    """A body with no length, which httpx sends with ``Transfer-Encoding: chunked``."""
    yield body


@pytest.fixture
def settings() -> Settings:
    return Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        # TestClient sends `Host: testserver`.
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
    )


@pytest.fixture
def assembly() -> Assembly:
    built = assemble(clock=lambda: NOON)
    built.ledger.add(HELD)
    return built


@pytest.fixture
def app(settings: Settings, assembly: Assembly, token_verifier: TokenVerifier) -> FastAPI:
    return create_app(settings, assembly=assembly, token_verifier=token_verifier)


def _problem(response: Any) -> dict[str, Any]:
    assert response.headers["content-type"] == PROBLEM_JSON
    body: dict[str, Any] = response.json()
    return body


def Given_the_body_limit_When_read_Then_it_is_the_64_KiB_every_service_enforces() -> None:
    assert MAX_REQUEST_BODY_BYTES == AGREED_BODY_LIMIT_BYTES


def Given_a_content_length_over_the_limit_When_posted_Then_it_is_refused_413_before_it_is_read(
    app: FastAPI, owner_headers: dict[str, str]
) -> None:
    recorder = _ReadRecorder(app)
    with TestClient(recorder, headers=owner_headers) as client:
        response = client.post(
            DISPOSAL_URL,
            content=_disposal_of_exactly(MAX_REQUEST_BODY_BYTES + 1),
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 413
    problem = _problem(response)
    assert problem["code"] == CONTENT_TOO_LARGE
    assert problem["status"] == 413
    assert recorder.reads.messages == 0
    # Refused inside the correlation middleware, so the refusal can be joined to its log line.
    assert response.headers[CORRELATION_ID_HEADER]


def Given_no_token_and_a_content_length_over_the_limit_When_posted_Then_it_is_413_not_401(
    app: FastAPI,
) -> None:
    """Refused before authentication: deciding who asked never waits on a body of any size."""
    recorder = _ReadRecorder(app)
    with TestClient(recorder) as client:
        response = client.post(
            DISPOSAL_URL,
            content=_disposal_of_exactly(MAX_REQUEST_BODY_BYTES + 1),
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 413
    assert _problem(response)["code"] == CONTENT_TOO_LARGE
    assert recorder.reads.messages == 0


def Given_no_token_and_a_chunked_body_over_the_limit_When_posted_Then_it_is_401_and_never_read(
    app: FastAPI,
) -> None:
    """A body that declares no length is counted only as the route reads it, after
    authentication, so a caller with no token is refused before any of it is read."""
    recorder = _ReadRecorder(app, chunk=CHUNK_BYTES)
    with TestClient(recorder) as client:
        response = client.post(
            DISPOSAL_URL,
            content=_chunked(_disposal_of_exactly(4 * MAX_REQUEST_BODY_BYTES)),
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 401
    assert recorder.reads.messages == 0


def Given_a_chunked_body_over_the_limit_When_posted_Then_it_is_refused_413_once_it_crosses_it(
    app: FastAPI, owner_headers: dict[str, str]
) -> None:
    body = _disposal_of_exactly(4 * MAX_REQUEST_BODY_BYTES)
    recorder = _ReadRecorder(app, chunk=CHUNK_BYTES)
    with TestClient(recorder, headers=owner_headers) as client:
        response = client.post(
            DISPOSAL_URL, content=_chunked(body), headers={"content-type": "application/json"}
        )

    assert response.status_code == 413
    problem = _problem(response)
    assert problem["code"] == CONTENT_TOO_LARGE
    # Its detail is the one a declared length is refused with: one refusal, read one way.
    with TestClient(app, headers=owner_headers) as client:
        declared = client.post(
            DISPOSAL_URL,
            content=_disposal_of_exactly(MAX_REQUEST_BODY_BYTES + 1),
            headers={"content-type": "application/json"},
        )
    assert problem == _problem(declared)
    # Reading stopped at the message that crossed the limit, not at the end of the body.
    assert recorder.reads.body_bytes == MAX_REQUEST_BODY_BYTES + CHUNK_BYTES


@pytest.mark.parametrize("chunked", [False, True], ids=["content-length", "chunked"])
def Given_a_body_exactly_at_the_limit_When_a_disposal_is_priced_Then_it_is_read_and_priced(
    app: FastAPI, owner_headers: dict[str, str], chunked: bool
) -> None:
    body = _disposal_of_exactly(MAX_REQUEST_BODY_BYTES)
    recorder = _ReadRecorder(app, chunk=CHUNK_BYTES if chunked else None)
    with TestClient(recorder, headers=owner_headers) as client:
        response = client.post(
            DISPOSAL_URL,
            content=_chunked(body) if chunked else body,
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 200
    assert response.json()["quantity"] == "12"
    assert recorder.reads.body_bytes == MAX_REQUEST_BODY_BYTES


@pytest.mark.parametrize("chunked", [False, True], ids=["content-length", "chunked"])
def Given_a_body_over_the_limit_When_refused_Then_one_warning_names_the_limit(
    app: FastAPI,
    owner_headers: dict[str, str],
    capsys: pytest.CaptureFixture[str],
    chunked: bool,
) -> None:
    """The ledger logs this refusal at WARN. The request line beside it is INFO whatever its
    status, so without a warning of its own the refusal vanishes once only warnings are kept."""
    body = _disposal_of_exactly(4 * MAX_REQUEST_BODY_BYTES)
    with TestClient(_ReadRecorder(app, chunk=CHUNK_BYTES), headers=owner_headers) as client:
        capsys.readouterr()
        response = client.post(
            DISPOSAL_URL,
            content=_chunked(body) if chunked else body,
            headers={"content-type": "application/json"},
        )

    records = [json.loads(line) for line in capsys.readouterr().out.splitlines() if line]
    warnings = [record for record in records if record["level"] == "warning"]
    assert response.status_code == 413
    assert [
        (record["event"], record["limit_bytes"], record["correlation_id"]) for record in warnings
    ] == [(BODY_REFUSED_EVENT, MAX_REQUEST_BODY_BYTES, response.headers[CORRELATION_ID_HEADER])]
