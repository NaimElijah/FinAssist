"""The logging boundary, the correlation id, and the endpoints that prove the app runs.

These hold the claims ADR-019 makes. The redaction tests matter most: the requirements say
secrets are redacted at the boundary rather than at each call site, and the only way that stays
true is if a test fails when the boundary stops working.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, NamedTuple, NoReturn

import httpx
import pytest
import structlog
import uvicorn
from fastapi import APIRouter
from fastapi.testclient import TestClient
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.util._once import Once
from pydantic import ValidationError
from starlette.requests import Request
from starlette.testclient import WebSocketDenialResponse

from finassist_assistant.adapters.__main__ import (
    MAX_HEADER_BLOCK_BYTES,
    WEBSOCKET_IMPLEMENTATION,
    main,
)
from finassist_assistant.adapters.app import (
    CORRELATION_ID_HEADER,
    _correlation_id_for,
    create_app,
)
from finassist_assistant.adapters.auth import TokenVerifier
from finassist_assistant.adapters.observability import (
    REDACTED,
    configure_logging,
    configure_tracing,
    redact_secrets,
    register_secret,
    reset_logging,
    trace_id_of,
)
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import assemble

SECRET = "sk-ant-000000000000000000000000"
VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
TRACEPARENT = f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"
REQUEST_LINE = "request answered"
HOST_REFUSED_EVENT = "refused a request whose Host header names no host on the allow-list"
# A recognisable amount, sent in a query string that must never reach a log line.
AMOUNT = "98765.43"
# The longest a test server may take to answer or to stop, so a hang fails rather than stalls.
_SERVER_STOP_SECONDS = 10.0


@pytest.fixture
def settings() -> Settings:
    return Settings(
        ASSISTANT_SERVICE_NAME="finassist-assistant",
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`, which the allow-list must admit (ADR-030).
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
        # A plain string is what the environment supplies; pydantic coerces it.
        ANTHROPIC_API_KEY=SECRET,  # type: ignore[arg-type]
    )


@pytest.fixture
def client(
    settings: Settings, token_verifier: TokenVerifier, owner_headers: dict[str, str]
) -> Iterator[TestClient]:
    app = create_app(settings, token_verifier=token_verifier)
    with TestClient(app, headers=owner_headers) as running:
        yield running


def _last_record(captured: str) -> dict[str, Any]:
    record: dict[str, Any] = json.loads(captured.strip().splitlines()[-1])
    return record


def _request_with(headers: dict[str, str]) -> Request:
    raw = [(key.lower().encode(), value.encode()) for key, value in headers.items()]
    return Request({"type": "http", "method": "GET", "path": "/health", "headers": raw})


def _records(captured: str) -> list[dict[str, Any]]:
    return [json.loads(line) for line in captured.strip().splitlines() if line.startswith("{")]


def _request_lines(captured: str) -> list[dict[str, Any]]:
    return [record for record in _records(captured) if record.get("event") == REQUEST_LINE]


# --- the endpoints -------------------------------------------------------------------------


def Given_the_app_When_health_is_requested_Then_it_reports_up(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def Given_the_app_When_readiness_is_requested_Then_it_reports_up(client: TestClient) -> None:
    response = client.get("/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def Given_an_assembly_When_the_app_is_built_with_it_Then_the_app_holds_that_assembly(
    settings: Settings, token_verifier: TokenVerifier
) -> None:
    """A test seeds an assembly's in-memory adapters, so the app must serve those instances."""
    assembly = assemble()

    app = create_app(settings, assembly=assembly, token_verifier=token_verifier)

    assert app.state.assembly is assembly


# --- correlation ---------------------------------------------------------------------------


def Given_no_traceparent_When_a_request_is_served_Then_a_correlation_id_is_returned(
    client: TestClient,
) -> None:
    response = client.get("/health")

    correlation_id = response.headers[CORRELATION_ID_HEADER]
    assert len(correlation_id) == 32
    assert int(correlation_id, 16) >= 0


def Given_a_traceparent_When_a_request_is_served_Then_its_trace_id_is_continued(
    client: TestClient,
) -> None:
    """A caller's trace must not be restarted, or the two halves never join up."""
    response = client.get(
        "/health", headers={"traceparent": f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"}
    )

    assert response.headers[CORRELATION_ID_HEADER] == VALID_TRACE_ID


@pytest.mark.parametrize(
    "traceparent",
    [
        "",
        "not-a-header",
        "00-abc-00f067aa0ba902b7-01",
        f"00-{'Z' * 32}-00f067aa0ba902b7-01",
        f"00-{'0' * 32}-00f067aa0ba902b7-01",
        f"00-{VALID_TRACE_ID}",
        # `$` also matches before a final newline, so this one was accepted, newline and all.
        f"00-{VALID_TRACE_ID}\n-00f067aa0ba902b7-01",
    ],
    ids=["empty", "prose", "short", "non-hex", "all-zero", "too-few-fields", "trailing-newline"],
)
def Given_a_malformed_traceparent_When_parsed_Then_it_is_refused(traceparent: str) -> None:
    """The header is caller-supplied and ends up in a log line and a response header.

    The web client's ``traceIdOf`` applies exactly this rule. Two implementations of one header
    format that disagree is how malformed data gets in through the more permissive side.
    """
    assert trace_id_of(traceparent) is None


def Given_a_malformed_traceparent_When_a_request_is_served_Then_a_fresh_id_is_used(
    client: TestClient,
) -> None:
    response = client.get("/health", headers={"traceparent": f"00-{'Z' * 32}-00f067aa0ba902b7-01"})

    correlation_id = response.headers[CORRELATION_ID_HEADER]
    assert correlation_id != "Z" * 32
    assert int(correlation_id, 16) >= 0


def Given_an_active_span_When_the_correlation_id_is_chosen_Then_the_span_wins() -> None:
    """The header and the log field must come from one source.

    An earlier version minted a fresh uuid for the response header while the log lines carried
    the span's trace id, so the id a caller was handed matched nothing in the logs. The span
    has to win, because that is the id the collector records.
    """
    tracer = TracerProvider().get_tracer("test")
    request = _request_with({"traceparent": f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"})

    with tracer.start_as_current_span("incoming") as span:
        expected = format(span.get_span_context().trace_id, "032x")

        assert _correlation_id_for(request) == expected


def Given_a_request_When_it_is_logged_Then_the_header_and_the_log_line_agree(
    settings: Settings, token_verifier: TokenVerifier, capsys: pytest.CaptureFixture[str]
) -> None:
    """The id a caller is handed in the response header is the one its request line carries."""
    app = create_app(settings, token_verifier=token_verifier)
    with TestClient(app) as running:
        capsys.readouterr()
        response = running.get(
            "/health", headers={"traceparent": f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"}
        )

    [line] = _request_lines(capsys.readouterr().out)
    assert response.headers[CORRELATION_ID_HEADER] == line["correlation_id"] == VALID_TRACE_ID


# --- the request line ----------------------------------------------------------------------


def Given_a_traceparent_When_a_request_is_served_Then_one_request_line_carries_its_trace_id(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """ADR-019's promise at the assistant's edge: the caller's trace id is in a line it wrote.

    The query string carries an amount on purpose. The line names the path alone, because a URL is
    not a safe place to assume nothing sensitive travels (ADR-028).
    """
    capsys.readouterr()

    client.get(
        "/health",
        params={"amount": AMOUNT},
        headers={"traceparent": f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"},
    )

    [line] = _request_lines(capsys.readouterr().out)
    assert line["correlation_id"] == VALID_TRACE_ID
    assert line["method"] == "GET"
    assert line["path"] == "/health"
    assert line["status"] == 200
    assert line["level"] == "info"
    assert isinstance(line["duration_ms"], float)
    assert line["duration_ms"] >= 0
    assert AMOUNT not in json.dumps(line)


@pytest.mark.parametrize(
    ("path", "headers", "status"),
    [
        ("/health", {"host": "attacker.example"}, 403),
        ("/no-such-route", {}, 404),
        ("/fails", {}, 500),
    ],
    ids=["refused-host", "unknown-route", "server-error"],
)
def Given_a_refused_unknown_or_failing_request_When_answered_Then_one_info_request_line_is_written(
    settings: Settings,
    token_verifier: TokenVerifier,
    owner_headers: dict[str, str],
    capsys: pytest.CaptureFixture[str],
    path: str,
    headers: dict[str, str],
    status: int,
) -> None:
    """Every request leaves exactly one line, at INFO whatever its status, with the caller's trace.

    A refusal is the assistant's considered answer, not an incident; an unexpected failure has its
    own ERROR line from the problem handlers, so the request line need not repeat the alarm.

    A refused Host carries the id as well. When the UI is loaded under a name the list does not
    admit, the refusal looks like an outage, and the web client's log of the 403 can be joined to
    the assistant's side only by the trace id both lines hold (ADR-019, ADR-030).
    """
    failing = APIRouter()

    @failing.get("/fails")
    def fails() -> None:
        raise RuntimeError("an unexpected failure")

    app = create_app(settings, token_verifier=token_verifier)
    app.include_router(failing)
    with TestClient(app, headers=owner_headers) as running:
        capsys.readouterr()
        response = running.get(path, headers={"traceparent": TRACEPARENT, **headers})

    lines = _request_lines(capsys.readouterr().out)
    assert response.status_code == status
    assert [
        (line["path"], line["status"], line["level"], line.get("correlation_id")) for line in lines
    ] == [(path, status, "info", VALID_TRACE_ID)]


@pytest.mark.parametrize(
    "broken",
    ["_correlation_id_for", "answer_unexpected"],
    ids=["choosing-the-id-fails", "answering-the-failure-fails"],
)
def Given_the_middleware_itself_fails_When_a_request_is_served_Then_one_request_line_records_a_500(
    settings: Settings,
    token_verifier: TokenVerifier,
    owner_headers: dict[str, str],
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
    broken: str,
) -> None:
    """The request line is written however the correlation middleware is left, not only on success.

    Choosing the id and answering an unexpected failure both run inside that middleware. When
    either raises, it produces no response, and Starlette's last resort answers instead; the request
    must still leave its one line, recorded as the 500 it becomes.
    """

    def fail(*_arguments: Any) -> NoReturn:
        raise RuntimeError("the correlation middleware itself failed")

    monkeypatch.setattr(f"finassist_assistant.adapters.app.{broken}", fail)
    failing = APIRouter()

    @failing.get("/fails")
    def fails() -> None:
        raise RuntimeError("an unexpected failure")

    app = create_app(settings, token_verifier=token_verifier)
    app.include_router(failing)
    with TestClient(app, headers=owner_headers, raise_server_exceptions=False) as running:
        capsys.readouterr()
        response = running.get("/fails")

    lines = _request_lines(capsys.readouterr().out)
    assert response.status_code == 500
    assert [(line["path"], line["status"], line["level"]) for line in lines] == [
        ("/fails", 500, "info")
    ]


def Given_a_foreign_host_When_refused_Then_one_warning_names_no_host(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """The ledger logs a Host refusal at WARN. The request line is INFO, so once only warnings are
    kept a rebinding attempt would otherwise leave no trace at all. The header is never logged: it
    is whatever the caller chose to send."""
    capsys.readouterr()

    response = client.get(
        "/health", headers={"host": "attacker.example", "traceparent": TRACEPARENT}
    )

    captured = capsys.readouterr().out
    warnings = [record for record in _records(captured) if record["level"] == "warning"]
    assert response.status_code == 403
    assert [(record["event"], record.get("correlation_id")) for record in warnings] == [
        (HOST_REFUSED_EVENT, VALID_TRACE_ID)
    ]
    assert "attacker.example" not in captured


def Given_a_foreign_host_When_a_websocket_upgrade_arrives_Then_it_is_refused_without_an_error(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """The allow-list checks WebSocket upgrades too, on the DNS-rebinding path it exists for.

    A WebSocket scope carries no method, and the request line records an HTTP request. The
    assistant serves no WebSocket route, so an admitted upgrade writes no line either, and the
    entry point's uvicorn performs no upgrade at all (``ws="none"``). So the refusal writes no
    request line, and must not fail on the way to answering.
    """
    capsys.readouterr()

    with (
        pytest.raises(WebSocketDenialResponse) as refused,
        client.websocket_connect("/health", headers={"host": "attacker.example"}),
    ):
        pass

    captured = capsys.readouterr().out
    assert refused.value.status_code == 403
    assert "Traceback" not in captured
    assert [record for record in _records(captured) if record["level"] == "error"] == []
    assert _request_lines(captured) == []
    assert [record["event"] for record in _records(captured) if record["level"] == "warning"] == [
        HOST_REFUSED_EVENT
    ]


class _Launch(NamedTuple):
    """What the entry point hands ``uvicorn.run``: the app, and every option it passes."""

    app: Any
    options: dict[str, Any]


def _entry_point_launch(monkeypatch: pytest.MonkeyPatch) -> _Launch:
    """Runs the entry point with ``uvicorn.run`` replaced, and returns what it was given."""
    launched: list[_Launch] = []

    def run(app: Any, **options: Any) -> None:
        launched.append(_Launch(app, options))

    monkeypatch.setenv("OTEL_EXPORT_ENABLED", "false")
    monkeypatch.setattr(uvicorn, "run", run)

    main()

    [launch] = launched
    return launch


def Given_the_entry_point_When_it_starts_the_server_Then_uvicorn_writes_no_access_log(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The app writes the request line while the correlation id is bound.

    Uvicorn's own access line is written afterwards, from its protocol layer, when nothing is bound,
    so leaving it on would add a second line per request that no id can join.
    """
    started = _entry_point_launch(monkeypatch).options

    assert started["access_log"] is False
    assert started["log_config"] is None


def Given_the_entry_point_When_it_starts_the_server_Then_the_header_block_is_bounded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``httptools``, which uvicorn picks when it is installed, buffers a header of any size."""
    started = _entry_point_launch(monkeypatch).options

    assert started.get("http") == "h11"
    assert started.get("h11_max_incomplete_event_size") == MAX_HEADER_BLOCK_BYTES


def Given_the_entry_point_When_it_starts_the_server_Then_proxy_and_server_headers_are_off(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The assistant trusts no proxy's forwarding headers, and names no server software."""
    started = _entry_point_launch(monkeypatch).options

    assert started.get("proxy_headers") is False
    assert started.get("server_header") is False


def Given_the_entry_point_When_it_starts_the_server_Then_no_websocket_is_upgraded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    started = _entry_point_launch(monkeypatch).options

    assert started.get("ws") == WEBSOCKET_IMPLEMENTATION == "none"


@contextmanager
def _serving_as_the_entry_point_does(
    launch: _Launch, monkeypatch: pytest.MonkeyPatch
) -> Iterator[tuple[str, int]]:
    """Serves *launch*'s app with *launch*'s own options, on an ephemeral loopback port.

    The socket is listening before the server thread starts, so a client may connect at once:
    the connection waits in the backlog until the server accepts it, and nothing polls for start.

    ``access_log=False`` makes uvicorn empty and detach its access logger for the whole process,
    so both are restored afterwards, or a later test writing an access line would find it gone.
    """
    access = logging.getLogger("uvicorn.access")
    monkeypatch.setattr(access, "handlers", access.handlers)
    monkeypatch.setattr(access, "propagate", access.propagate)
    listener = socket.create_server(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(launch.app, **launch.options))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    try:
        yield listener.getsockname()
    finally:
        server.should_exit = True
        thread.join(timeout=_SERVER_STOP_SECONDS)
        listener.close()
    assert not thread.is_alive(), "the server never stopped"


def _answer_to(address: tuple[str, int], request: bytes) -> bytes:
    """Sends *request* over a raw socket and reads the answer until the server closes."""
    with socket.create_connection(address, timeout=_SERVER_STOP_SECONDS) as connection:
        connection.sendall(request)
        answer = b""
        while chunk := connection.recv(65_536):
            answer += chunk
    return answer


def Given_headers_over_the_bound_When_the_entry_point_serves_them_Then_they_are_refused_400(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refused by the server before the app, and so before authentication, ever sees them.

    Exactly one byte over, with the header block left unfinished, so the server has read all of it
    when it refuses and closes, and the refusal arrives intact rather than behind a reset.
    """
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", "127.0.0.1")
    launch = _entry_point_launch(monkeypatch)
    start = b"GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nX-Padding: "
    oversized = start + b"a" * (MAX_HEADER_BLOCK_BYTES + 1 - len(start))

    with _serving_as_the_entry_point_does(launch, monkeypatch) as address:
        served = _answer_to(address, start[: -len(b"X-Padding: ")] + b"\r\n")
        refused = _answer_to(address, oversized)

    assert served.startswith(b"HTTP/1.1 200 ")
    assert b"\r\nserver:" not in served.lower()
    assert refused.startswith(b"HTTP/1.1 400 ")


def Given_a_header_block_just_under_the_bound_When_the_entry_point_serves_it_Then_it_is_200(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A large bearer token is still served: the bound limits what is buffered, not a token.

    The whole block is one byte under the bound. It guards against a later bound, in the server
    or in authentication, that would refuse a legitimate token; the 400 test above is what proves
    the bound itself, since a block that arrives whole is parsed whatever its size.
    """
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", "127.0.0.1")
    launch = _entry_point_launch(monkeypatch)
    start = (
        b"GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAuthorization: Bearer "
    )
    end = b"\r\n\r\n"
    token = b"a" * (MAX_HEADER_BLOCK_BYTES - 1 - len(start) - len(end))

    with _serving_as_the_entry_point_does(launch, monkeypatch) as address:
        served = _answer_to(address, start + token + end)

    assert served.startswith(b"HTTP/1.1 200 ")


def Given_a_websocket_upgrade_When_the_entry_point_serves_it_Then_it_is_answered_as_plain_http(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No handshake, so no ``101``: the request is served as the HTTP request it also is."""
    monkeypatch.setenv("ASSISTANT_ALLOWED_HOSTS", "127.0.0.1")
    launch = _entry_point_launch(monkeypatch)
    upgrade = (
        b"GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Upgrade, close\r\n"
        b"Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
        b"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
    )

    with _serving_as_the_entry_point_does(launch, monkeypatch) as address:
        answer = _answer_to(address, upgrade)

    assert answer.startswith(b"HTTP/1.1 200 ")


# --- redaction -----------------------------------------------------------------------------


def Given_a_secret_in_an_exception_When_logged_Then_the_traceback_is_scrubbed(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    """The likeliest carrier of a secret, and the one an ordering mistake silently exposes.

    ``format_exc_info`` renders the traceback into a string; redaction must run after it. With
    the two reversed there is nothing to scrub yet and the key reaches the log in full.
    """
    configure_logging(settings)

    try:
        raise ValueError(f"auth failed for {SECRET}")
    except ValueError:
        structlog.get_logger("test").exception("call failed")

    written = capsys.readouterr().out
    assert SECRET not in written
    assert REDACTED in written


def Given_a_secret_in_an_exception_When_logged_through_the_stdlib_Then_it_is_also_scrubbed(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    """Libraries log through ``logging``; that path shares the chain and must share the fix."""
    configure_logging(settings)

    try:
        raise ValueError(f"auth failed for {SECRET}")
    except ValueError:
        logging.getLogger("some.library").exception("call failed")

    assert SECRET not in capsys.readouterr().out


def Given_a_configured_key_When_it_appears_in_a_message_Then_it_is_scrubbed(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    """The value is caught even where no key names it, which is how a secret reaches a URL."""
    configure_logging(settings)

    structlog.get_logger("test").info(f"calling https://api.anthropic.com?key={SECRET}")

    written = capsys.readouterr().out
    assert SECRET not in written
    assert REDACTED in written


def Given_a_secret_named_field_When_logged_Then_its_value_never_reaches_the_output(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(settings)

    structlog.get_logger("test").info("outbound", api_key="whatever-this-is", password="hunter2")

    record = _last_record(capsys.readouterr().out)
    assert record["api_key"] == REDACTED
    assert record["password"] == REDACTED


class _CarriesItInItsRepr:
    """An object that shows the secret only when rendered, as a client or a response may."""

    def __repr__(self) -> str:
        return f"<client key={SECRET}>"


@pytest.mark.parametrize(
    "field",
    [
        ConnectionError(f"refused calling https://api.anthropic.com?key={SECRET}"),
        f"key={SECRET}".encode(),
        _CarriesItInItsRepr(),
    ],
    ids=["exception", "bytes", "object-with-a-repr"],
)
def Given_a_registered_secret_inside_a_non_string_field_When_rendered_Then_it_is_scrubbed(
    settings: Settings, capsys: pytest.CaptureFixture[str], field: object
) -> None:
    """The renderer turns any other value into text after redaction has run, so a secret inside
    one would reach the output unscrubbed. An outbound client logging its exception as a field is
    the likely first carrier."""
    configure_logging(settings)

    structlog.get_logger("test").warning("outbound call failed", error=field)

    written = capsys.readouterr().out
    assert SECRET not in written
    assert REDACTED in _last_record(written)["error"]


@pytest.mark.parametrize("field", [True, None, 1.5], ids=["bool", "none", "float"])
def Given_a_boolean_none_or_float_field_When_rendered_Then_it_keeps_its_json_type(
    settings: Settings, capsys: pytest.CaptureFixture[str], field: object
) -> None:
    configure_logging(settings)

    structlog.get_logger("test").info("measured", value=field)

    value = _last_record(capsys.readouterr().out)["value"]
    assert value == field
    assert type(value) is type(field)


def Given_a_decimal_field_When_rendered_Then_it_is_its_text_scrubbed(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    """Through ``str``, so ``12.50``, not ``Decimal('12.50')``, and scrubbed like any text."""
    configure_logging(settings)
    digits = "9876543210"
    register_secret(digits)

    structlog.get_logger("test").info("measured", plain=Decimal("12.50"), holding=Decimal(digits))

    record = _last_record(capsys.readouterr().out)
    assert record["plain"] == "12.50"
    assert record["holding"] == REDACTED


def Given_a_log_event_When_written_Then_it_is_one_json_object_per_line(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(settings)

    structlog.get_logger("test").info("a plain event", account="chequing")

    record = _last_record(capsys.readouterr().out)
    assert record["event"] == "a plain event"
    assert record["level"] == "info"
    assert record["account"] == "chequing"
    assert "timestamp" in record


def Given_an_httpx_client_When_it_sends_a_request_Then_its_url_is_not_logged(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    """httpx writes every request it sends at INFO, with the full URL and its query string. No
    outbound client exists yet, and the first must not put every URL it calls on a log line: a URL
    is no place to assume nothing sensitive travels. A warning from httpx still comes through."""
    configure_logging(settings)
    capsys.readouterr()

    answer_ok = httpx.MockTransport(lambda _request: httpx.Response(200))
    with httpx.Client(transport=answer_ok) as outbound:
        outbound.get(f"http://ledger.invalid/api/v1/accounts?amount={AMOUNT}")
    logging.getLogger("httpx").warning("the ledger answered slowly")

    captured = capsys.readouterr().out
    assert "ledger.invalid" not in captured
    assert AMOUNT not in captured
    assert "the ledger answered slowly" in captured


def Given_logging_was_configured_When_it_is_reset_Then_httpx_logs_at_its_own_level_again(
    settings: Settings,
) -> None:
    """The level is process-wide state, so it is restored with the root logger's."""
    before = logging.getLogger("httpx").level

    configure_logging(settings)
    quieted = logging.getLogger("httpx").level
    reset_logging()

    assert (quieted, logging.getLogger("httpx").level) == (logging.WARNING, before)


@pytest.mark.parametrize(
    ("event", "expected"),
    [
        ({"authorization": "Bearer abc"}, {"authorization": REDACTED}),
        ({"X-Api-Key": "abc"}, {"X-Api-Key": REDACTED}),
        ({"refresh_token": "abc"}, {"refresh_token": REDACTED}),
        ({"account": "chequing"}, {"account": "chequing"}),
        # The assistance context exists to report LLM usage; a bare `token` fragment would
        # redact the measurement while protecting nothing.
        ({"input_tokens": 1200}, {"input_tokens": 1200}),
        ({"output_tokens": 340}, {"output_tokens": 340}),
    ],
)
def Given_a_field_When_redacted_Then_only_secret_named_keys_are_removed(
    event: dict[str, Any], expected: dict[str, Any]
) -> None:
    assert redact_secrets(None, "info", dict(event)) == expected


def Given_a_secret_nested_in_a_payload_When_redacted_Then_it_is_still_removed() -> None:
    """A flat scan would miss this, and a payload is exactly how a secret travels."""
    event = {"request": {"headers": {"Authorization": "Bearer abc"}, "path": "/v1/messages"}}

    redacted = redact_secrets(None, "info", event)

    assert redacted["request"]["headers"]["Authorization"] == REDACTED
    assert redacted["request"]["path"] == "/v1/messages"


_CYCLE_DESCENT_BOUND = 100
"""Far deeper than any payload a log line should carry; reaching it means the descent never
stopped."""


def Given_a_cyclic_payload_When_redacted_Then_it_terminates() -> None:
    """Unbounded descent turns a log call into a RecursionError, which is worse than the line
    it failed to write. The web client's redactor is bounded for the same reason."""
    cyclic: dict[str, Any] = {"name": "loop"}
    cyclic["self"] = cyclic

    redacted = redact_secrets(None, "info", {"payload": cyclic})

    # The descent stops at a bounded depth and puts the redaction marker where it stopped, so the
    # value returned holds no cycle; what it passed through on the way keeps its ordinary members.
    level: Any = redacted["payload"]
    for _ in range(_CYCLE_DESCENT_BOUND):
        if not isinstance(level, dict):
            break
        level = level["self"]
    assert level == REDACTED
    assert redacted["payload"]["name"] == "loop"


def Given_a_short_value_When_registered_Then_it_is_ignored() -> None:
    """Scrubbing a two-character secret would replace fragments of every ordinary word."""
    register_secret("ab")

    assert redact_secrets(None, "info", {"event": "a table of absolute values"}) == {
        "event": "a table of absolute values"
    }


# --- settings ------------------------------------------------------------------------------


def Given_an_api_key_When_the_settings_are_printed_Then_the_value_is_not_shown() -> None:
    """``Settings`` is passed around; one ``repr`` of it must not put the key on stdout.

    The redaction boundary covers log events, not the accidental printing of this object, so
    the type has to carry the guarantee.
    """
    settings = Settings(ANTHROPIC_API_KEY=SECRET)  # type: ignore[arg-type]

    assert SECRET not in repr(settings)
    assert SECRET not in str(settings.model_dump())
    assert settings.anthropic_api_key_value == SECRET


def Given_an_unknown_log_level_When_settings_are_built_Then_the_variable_is_named() -> None:
    """A bare ``KeyError`` from inside the logging setup says nothing about what is wrong.

    The message names the environment variable, which is what a developer actually sets, and
    lists the legal values.
    """
    with pytest.raises(ValidationError) as failure:
        # Deliberately illegal: the point of the test is that it is refused.
        Settings(ASSISTANT_LOG_LEVEL="verbose")  # type: ignore[arg-type]

    message = str(failure.value)
    assert "ASSISTANT_LOG_LEVEL" in message
    assert "DEBUG" in message and "CRITICAL" in message


@pytest.mark.parametrize("port", ["", "abc", "0", "70000"])
def Given_an_invalid_port_When_settings_are_built_Then_it_is_refused(port: str) -> None:
    """``int('')`` is exactly how a blank value in a copied .env file fails today."""
    with pytest.raises(ValidationError):
        Settings(ASSISTANT_PORT=port)  # type: ignore[arg-type]


def Given_no_host_When_settings_are_built_Then_it_binds_loopback() -> None:
    """Nothing runs the assistant in a container yet, so every interface would mean the LAN."""
    assert Settings().host == "127.0.0.1"


def Given_no_endpoint_When_settings_are_read_Then_tracing_is_disabled() -> None:
    """ADR-019's no-op contract: the service runs with no collector configured."""
    assert not Settings(OTEL_EXPORTER_OTLP_ENDPOINT=None, OTEL_EXPORT_ENABLED=True).traces_enabled


def Given_an_endpoint_but_no_permission_When_settings_are_read_Then_tracing_is_disabled() -> None:
    """A stale endpoint copied between machines should go quiet rather than retry."""
    assert not Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318", OTEL_EXPORT_ENABLED=False
    ).traces_enabled


def Given_both_an_endpoint_and_permission_When_settings_are_read_Then_tracing_is_enabled() -> None:
    assert Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318", OTEL_EXPORT_ENABLED=True
    ).traces_enabled


def Given_tracing_is_disabled_When_configured_Then_no_provider_is_installed() -> None:
    assert configure_tracing(Settings(OTEL_EXPORT_ENABLED=False)) is None


# --- tracing enabled: the OTLP provider is installed, and only once ---------------------------

_UNUSED_COLLECTOR = "http://127.0.0.1:4318"
"""Named, never reached: no test using it starts a span while the provider is installed, so its
batch processor's queue is empty at shutdown and it sends nothing - no collector, no network."""

_COLLECTOR_POLL_SECONDS = 0.01
"""How often the test collector checks for ``shutdown``; the 0.5 s default only slows teardown."""

_COLLECTOR_JOIN_SECONDS = 5.0
"""The longest teardown waits for the test collector's thread, so a stuck server fails the run
instead of hanging it."""

_FLUSH_TIMEOUT_MILLIS = 5_000
"""The longest ``force_flush`` waits for the exporter to hear back from the test collector."""


@pytest.fixture
def otlp_collector() -> Iterator[tuple[str, list[str]]]:
    """A loopback stand-in for an OTLP/HTTP collector, recording the path of every POST it gets.

    Yields its base URL and the recorded paths. A path is recorded before the answer is sent, and
    the exporter waits for that answer, so once ``force_flush`` returns the list is complete.

    The path is read from the raw request line, not ``self.path``: ``http.server`` collapses a
    leading ``//`` to ``/`` there, which would hide the doubled slash a test looks for.
    """
    posted: list[str] = []

    class _Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            _method, target, _version = self.requestline.split()
            posted.append(target)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, _format: str, *_args: Any) -> None:
            pass  # silences http.server's default stderr access log during the test run

    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(
        target=server.serve_forever,
        kwargs={"poll_interval": _COLLECTOR_POLL_SECONDS},
        daemon=True,
    )
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", posted
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=_COLLECTOR_JOIN_SECONDS)


@pytest.fixture
def fresh_tracer_provider_global(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Gives one test a process whose global tracer provider has never been set, then restores it.

    OpenTelemetry lets ``set_tracer_provider`` succeed once per process and offers no public
    reset, so this swaps its two module globals for fresh ones and lets ``monkeypatch`` put the
    originals back. Private names of a library this project does not own, which is the price of
    testing an install-once global at all; OpenTelemetry's own test utilities reset the same two.
    Whatever provider the test installed is shut down first, so no batch thread outlives it.
    """
    monkeypatch.setattr(trace, "_TRACER_PROVIDER", None)
    monkeypatch.setattr(trace, "_TRACER_PROVIDER_SET_ONCE", Once())
    yield
    installed = trace.get_tracer_provider()
    if isinstance(installed, TracerProvider):
        installed.shutdown()


def _export_enabled() -> Settings:
    return Settings(
        ASSISTANT_SERVICE_NAME="finassist-assistant-under-test",
        OTEL_EXPORTER_OTLP_ENDPOINT=_UNUSED_COLLECTOR,
        OTEL_EXPORT_ENABLED=True,
    )


@pytest.mark.usefixtures("fresh_tracer_provider_global")
def Given_export_enabled_When_tracing_is_configured_Then_a_named_provider_is_installed() -> None:
    provider = configure_tracing(_export_enabled())

    assert isinstance(provider, TracerProvider)
    assert trace.get_tracer_provider() is provider
    assert provider.resource.attributes["service.name"] == "finassist-assistant-under-test"


@pytest.mark.usefixtures("fresh_tracer_provider_global")
def Given_export_enabled_When_a_span_ends_Then_it_is_posted_to_the_collectors_traces_path(
    otlp_collector: tuple[str, list[str]],
) -> None:
    """The installed provider exports: one ended span reaches ``/v1/traces`` under the configured
    base URL. The base carries a trailing slash, as an operator's often does, and must still not
    produce ``//v1/traces``."""
    base_url, posted = otlp_collector
    configure_tracing(
        Settings(
            ASSISTANT_SERVICE_NAME="finassist-assistant-under-test",
            OTEL_EXPORTER_OTLP_ENDPOINT=f"{base_url}/",
            OTEL_EXPORT_ENABLED=True,
        )
    )

    with trace.get_tracer(__name__).start_as_current_span("exported"):
        pass
    installed = trace.get_tracer_provider()
    assert isinstance(installed, TracerProvider)
    assert installed.force_flush(timeout_millis=_FLUSH_TIMEOUT_MILLIS), "the export did not finish"

    assert posted == ["/v1/traces"], f"expected one POST to /v1/traces, the collector got {posted}"


@pytest.mark.usefixtures("fresh_tracer_provider_global")
def Given_a_provider_already_installed_When_tracing_is_configured_Then_it_is_refused() -> None:
    """OpenTelemetry would only log a warning and keep the first provider, so the caller would
    be handed a pipeline that is not running; ``configure_tracing`` refuses instead."""
    first = TracerProvider()
    trace.set_tracer_provider(first)

    with pytest.raises(RuntimeError, match="already installed"):
        configure_tracing(_export_enabled())
    assert trace.get_tracer_provider() is first
