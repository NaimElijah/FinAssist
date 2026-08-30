"""The logging boundary and the telemetry bootstrap.

The requirements say the owner's data and ``ANTHROPIC_API_KEY`` are "redacted at the logging
boundary, not at each call site". This module is that boundary: redaction is a structlog
processor every event passes through, so a caller cannot forget it and a new call site inherits
it for free.

Redaction works two ways, because one is not enough. Keys are matched by name, which catches
``{"api_key": ...}`` however it was built. Values are scrubbed by registration, which catches a
secret that has been interpolated into a message or a URL where no key names it.

See ADR-019.
"""

from __future__ import annotations

import logging
import sys
from collections.abc import Iterable, MutableMapping
from contextvars import ContextVar
from typing import Any, Final

import structlog
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from finassist_assistant.adapters.settings import Settings

REDACTED: Final = "[redacted]"

# Substrings, not exact names, so `anthropic_api_key` and `x-api-key` are both caught without
# listing every spelling anyone might use.
_SECRET_KEY_FRAGMENTS: Final = frozenset(
    {"api_key", "apikey", "authorization", "credential", "password", "secret", "token"}
)

# Registered secret values, scrubbed wherever they appear in a rendered string. Populated at
# startup from settings rather than hard-coded, so nothing here holds a secret itself.
_secret_values: set[str] = set()

# The correlation id for the request in flight. A ContextVar rather than a parameter because
# every log call would otherwise have to thread it through, which is the per-call-site coupling
# this module exists to avoid.
_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)

TRACEPARENT_HEADER: Final = "traceparent"
CORRELATION_ID_FIELD: Final = "correlation_id"


def register_secret(value: str | None) -> None:
    """Register a value to be scrubbed from every log event.

    Short values are ignored: a one- or two-character secret would match constantly and turn
    every message into redaction markers, which destroys the logs without protecting anything.
    """
    if value and len(value) >= 8:
        _secret_values.add(value)


def forget_secrets() -> None:
    """Drop every registered secret. For tests, which must not leak state into each other."""
    _secret_values.clear()


def set_correlation_id(value: str | None) -> None:
    """Bind the correlation id for the current context."""
    _correlation_id.set(value)


def current_correlation_id() -> str | None:
    """The correlation id bound to the current context, if any."""
    return _correlation_id.get()


def _is_secret_key(key: str) -> bool:
    """Match a key against the secret fragments, separator-insensitively.

    Hyphens are folded to underscores first: HTTP spells it ``X-Api-Key`` and Python spells it
    ``api_key``, and a boundary that caught only one of those would leak the other.
    """
    normalised = key.lower().replace("-", "_")
    return any(fragment in normalised for fragment in _SECRET_KEY_FRAGMENTS)


def _scrub(value: str) -> str:
    for secret in _secret_values:
        value = value.replace(secret, REDACTED)
    return value


def _redact_value(value: Any) -> Any:
    """Redact recursively, so a secret nested in a payload is not missed."""
    if isinstance(value, str):
        return _scrub(value)
    if isinstance(value, dict):
        return {
            key: REDACTED if _is_secret_key(str(key)) else _redact_value(item)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        rebuilt = [_redact_value(item) for item in value]
        return tuple(rebuilt) if isinstance(value, tuple) else rebuilt
    return value


def redact_secrets(
    _logger: Any, _method_name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """structlog processor: remove secret material before anything is rendered.

    :param event_dict: the event being logged, mutated in place and returned.
    :returns: the same mapping with secret-named keys replaced and registered values scrubbed.
    """
    for key in list(event_dict):
        if _is_secret_key(key):
            event_dict[key] = REDACTED
        else:
            event_dict[key] = _redact_value(event_dict[key])
    return event_dict


def add_correlation_id(
    _logger: Any, _method_name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """structlog processor: put the current correlation id on every line.

    Prefers the active span's trace id, so a log line and the span it happened inside carry the
    same value, and falls back to the id bound by the middleware when no span is recording.
    """
    span = trace.get_current_span()
    context = span.get_span_context()
    if context.is_valid:
        event_dict[CORRELATION_ID_FIELD] = format(context.trace_id, "032x")
    else:
        correlation_id = _correlation_id.get()
        if correlation_id is not None:
            event_dict[CORRELATION_ID_FIELD] = correlation_id
    return event_dict


def configure_logging(settings: Settings) -> None:
    """Configure structlog to emit JSON on stdout, through the redaction boundary.

    Rebinds the stdlib root logger too, so a library that logs through ``logging`` is rendered
    in the same format instead of bypassing the boundary with its own.
    """
    register_secret(settings.anthropic_api_key)

    shared_processors: Iterable[structlog.typing.Processor] = (
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        add_correlation_id,
        redact_secrets,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    )

    structlog.configure(
        processors=[*shared_processors, structlog.processors.JSONRenderer()],
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelNamesMapping()[settings.log_level.upper()]
        ),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )

    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(
        structlog.stdlib.ProcessorFormatter(
            processors=[
                structlog.stdlib.ProcessorFormatter.remove_processors_meta,
                structlog.processors.JSONRenderer(),
            ],
            foreign_pre_chain=list(shared_processors),
        )
    )
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(settings.log_level.upper())


def configure_tracing(settings: Settings) -> TracerProvider | None:
    """Install an OTLP tracer provider, or none at all.

    :returns: the provider when export is enabled, otherwise ``None``.

    Returning ``None`` rather than installing a provider with no exporter is deliberate: it
    makes "telemetry is off" observable to a test, and it keeps the OpenTelemetry default no-op
    tracer in place, which costs nothing per span.
    """
    if not settings.traces_enabled:
        return None

    endpoint = f"{str(settings.otlp_endpoint).rstrip('/')}/v1/traces"
    provider = TracerProvider(resource=Resource.create({"service.name": settings.service_name}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    trace.set_tracer_provider(provider)
    return provider
