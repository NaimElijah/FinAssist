"""The logging boundary, the correlation id, and the endpoints that prove the app runs.

These hold the claims ADR-019 makes. The redaction tests matter most: the requirements say
secrets are redacted at the boundary rather than at each call site, and the only way that stays
true is if a test fails when the boundary stops working.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

import pytest
import structlog
from fastapi.testclient import TestClient

from finassist_assistant.adapters.app import CORRELATION_ID_HEADER, create_app
from finassist_assistant.adapters.observability import (
    REDACTED,
    configure_logging,
    forget_secrets,
    redact_secrets,
    register_secret,
)
from finassist_assistant.adapters.settings import Settings

SECRET = "sk-ant-000000000000000000000000"


@pytest.fixture(autouse=True)
def _reset_observability() -> Iterator[None]:
    """Registered secrets and structlog configuration are global; no test may leak either."""
    forget_secrets()
    yield
    forget_secrets()
    structlog.reset_defaults()


@pytest.fixture
def settings() -> Settings:
    return Settings(
        ASSISTANT_SERVICE_NAME="finassist-assistant",
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_TRACES_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        ANTHROPIC_API_KEY=SECRET,
    )


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as running:
        yield running


def Given_the_app_When_health_is_requested_Then_it_reports_up(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def Given_the_app_When_readiness_is_requested_Then_it_reports_up(client: TestClient) -> None:
    response = client.get("/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


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
    trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
    response = client.get("/health", headers={"traceparent": f"00-{trace_id}-00f067aa0ba902b7-01"})

    assert response.headers[CORRELATION_ID_HEADER] == trace_id


def Given_a_malformed_traceparent_When_a_request_is_served_Then_a_fresh_id_is_used(
    client: TestClient,
) -> None:
    """A broken header is not a reason to refuse the request, nor to propagate nonsense."""
    response = client.get("/health", headers={"traceparent": "not-a-valid-header"})

    assert len(response.headers[CORRELATION_ID_HEADER]) == 32


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

    record = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert record["api_key"] == REDACTED
    assert record["password"] == REDACTED


def Given_a_log_event_When_written_Then_it_is_one_json_object_per_line(
    settings: Settings, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(settings)

    structlog.get_logger("test").info("a plain event", account="chequing")

    record = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert record["event"] == "a plain event"
    assert record["level"] == "info"
    assert record["account"] == "chequing"
    assert "timestamp" in record


@pytest.mark.parametrize(
    ("event", "expected"),
    [
        ({"authorization": "Bearer abc"}, {"authorization": REDACTED}),
        ({"X-Api-Key": "abc"}, {"X-Api-Key": REDACTED}),
        ({"user_token": "abc"}, {"user_token": REDACTED}),
        ({"account": "chequing"}, {"account": "chequing"}),
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


def Given_a_short_value_When_registered_Then_it_is_ignored() -> None:
    """Scrubbing a two-character secret would replace fragments of every ordinary word."""
    register_secret("ab")

    assert redact_secrets(None, "info", {"event": "a table of absolute values"}) == {
        "event": "a table of absolute values"
    }


def Given_no_endpoint_When_settings_are_read_Then_tracing_is_disabled() -> None:
    """ADR-019's no-op contract: the service runs with no collector configured."""
    assert not Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None, OTEL_TRACES_EXPORT_ENABLED=True
    ).traces_enabled


def Given_an_endpoint_but_no_permission_When_settings_are_read_Then_tracing_is_disabled() -> None:
    """A stale endpoint copied between machines should go quiet rather than retry."""
    assert not Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318", OTEL_TRACES_EXPORT_ENABLED=False
    ).traces_enabled


def Given_both_an_endpoint_and_permission_When_settings_are_read_Then_tracing_is_enabled() -> None:
    assert Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4318", OTEL_TRACES_EXPORT_ENABLED=True
    ).traces_enabled
