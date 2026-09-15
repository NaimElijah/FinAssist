"""The logging boundary, the correlation id, and the endpoints that prove the app runs.

These hold the claims ADR-019 makes. The redaction tests matter most: the requirements say
secrets are redacted at the boundary rather than at each call site, and the only way that stays
true is if a test fails when the boundary stops working.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Iterator
from typing import Any, NoReturn

import pytest
import structlog
import uvicorn
from fastapi import APIRouter
from fastapi.testclient import TestClient
from opentelemetry.sdk.trace import TracerProvider
from pydantic import ValidationError
from starlette.requests import Request
from starlette.testclient import WebSocketDenialResponse

from finassist_assistant.adapters.__main__ import main
from finassist_assistant.adapters.app import (
    CORRELATION_ID_HEADER,
    _correlation_id_for,
    create_app,
)
from finassist_assistant.adapters.observability import (
    REDACTED,
    configure_logging,
    configure_tracing,
    forget_secrets,
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
# A recognisable amount, sent in a query string that must never reach a log line.
AMOUNT = "98765.43"


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
        ASSISTANT_SERVICE_NAME="finassist-assistant",
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`, which the allow-list must admit (ADR-027).
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
        # A plain string is what the environment supplies; pydantic coerces it.
        ANTHROPIC_API_KEY=SECRET,  # type: ignore[arg-type]
    )


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as running:
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
    settings: Settings,
) -> None:
    """A test seeds an assembly's in-memory adapters, so the app must serve those instances."""
    assembly = assemble()

    app = create_app(settings, assembly=assembly)

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
    ],
    ids=["empty", "prose", "short", "non-hex", "all-zero", "too-few-fields"],
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
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    with TestClient(create_app(settings)) as running:
        capsys.readouterr()
        response = running.get(
            "/health", headers={"traceparent": f"00-{VALID_TRACE_ID}-00f067aa0ba902b7-01"}
        )

    structlog.get_logger("t").info("probe")
    assert response.headers[CORRELATION_ID_HEADER] == VALID_TRACE_ID


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
    the assistant's side only by the trace id both lines hold (ADR-019, ADR-027).
    """
    failing = APIRouter()

    @failing.get("/fails")
    def fails() -> None:
        raise RuntimeError("an unexpected failure")

    app = create_app(settings)
    app.include_router(failing)
    with TestClient(app) as running:
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

    app = create_app(settings)
    app.include_router(failing)
    with TestClient(app, raise_server_exceptions=False) as running:
        capsys.readouterr()
        response = running.get("/fails")

    lines = _request_lines(capsys.readouterr().out)
    assert response.status_code == 500
    assert [(line["path"], line["status"], line["level"]) for line in lines] == [
        ("/fails", 500, "info")
    ]


def Given_a_foreign_host_When_a_websocket_upgrade_arrives_Then_it_is_refused_without_an_error(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """The allow-list checks WebSocket upgrades too, on the DNS-rebinding path it exists for.

    A WebSocket scope carries no method, and the request line records an HTTP request. The
    assistant serves no WebSocket route, so an admitted upgrade writes no line either, and uvicorn
    reports a handshake it refused on its own logger. So the refusal writes no request line, and
    must not fail on the way to answering.
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


def Given_the_entry_point_When_it_starts_the_server_Then_uvicorn_writes_no_access_log(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The app writes the request line while the correlation id is bound.

    Uvicorn's own access line is written afterwards, from its protocol layer, when nothing is bound,
    so leaving it on would add a second line per request that no id can join.
    """
    started: dict[str, Any] = {}

    def run(_app: Any, **options: Any) -> None:
        started.update(options)

    monkeypatch.setenv("OTEL_EXPORT_ENABLED", "false")
    monkeypatch.setattr(uvicorn, "run", run)

    main()

    assert started["access_log"] is False
    assert started["log_config"] is None


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


def Given_a_cyclic_payload_When_redacted_Then_it_terminates() -> None:
    """Unbounded descent turns a log call into a RecursionError, which is worse than the line
    it failed to write. The web client's redactor is bounded for the same reason."""
    cyclic: dict[str, Any] = {"name": "loop"}
    cyclic["self"] = cyclic

    assert redact_secrets(None, "info", {"payload": cyclic}) is not None


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
