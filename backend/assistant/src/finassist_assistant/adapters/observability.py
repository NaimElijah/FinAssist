"""The logging boundary and the telemetry bootstrap.

The requirements say the owner's data and ``ANTHROPIC_API_KEY`` are "redacted at the logging
boundary, not at each call site". This module is that boundary: redaction is a structlog
processor every event passes through, so a caller cannot forget it and a new call site inherits
it for free.

Redaction works two ways, because one is not enough. Keys are matched by name, which catches
``{"api_key": ...}`` however it was built. Values are scrubbed by registration, which catches a
secret that has been interpolated into a message, a URL, or an exception traceback.

See ADR-019.
"""

from __future__ import annotations

import logging
import re
import sys
import time
from collections.abc import Iterable, MutableMapping
from contextvars import ContextVar
from typing import Any, Final, TextIO

import structlog
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from starlette.types import Scope

from finassist_assistant.adapters.settings import Settings

REDACTED: Final = "[redacted]"

# Substrings, not exact names, so `anthropic_api_key` and `x-api-key` are both caught without
# listing every spelling anyone might use. Matched against a form with `-` folded to `_`.
#
# Deliberately NOT a bare `token`: the assistance context's whole job is to record LLM usage,
# and `input_tokens=1200` is the natural way to log it. A fragment that redacted a token count
# would destroy the measurement while protecting nothing.
_SECRET_KEY_FRAGMENTS: Final = frozenset(
    {
        "api_key",
        "apikey",
        "auth_token",
        "authorization",
        "access_token",
        "credential",
        "id_token",
        "password",
        "refresh_token",
        "secret",
    }
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

# A W3C trace id is 32 lowercase hex characters, and all-zero is explicitly invalid.
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")
_INVALID_TRACE_ID: Final = "0" * 32
_TRACEPARENT_FIELDS: Final = 4

REQUEST_EVENT: Final = "request answered"
"""The event of the one line the assistant writes for every request it answers."""

_MILLISECONDS_PER_SECOND: Final = 1000
# Microseconds: finer than anything a request's duration is read for, and short on the line.
_DURATION_DECIMALS: Final = 3

_log = structlog.get_logger(__name__)

# Shortest value worth scrubbing. A two-character secret would match inside ordinary words and
# turn every message into redaction markers, destroying the logs without protecting anything.
_MIN_SECRET_LENGTH: Final = 8

# Redaction descends nested structures; this bounds it. A cyclic payload would otherwise turn a
# log call into a RecursionError, which is strictly worse than the line it failed to write.
_MAX_REDACTION_DEPTH: Final = 6

# httpx writes every request it sends at INFO as "HTTP Request: <method> <url>", the full URL with
# its query string. Held at WARNING whatever ASSISTANT_LOG_LEVEL says, so the first outbound client
# cannot put every URL it calls on a log line; a URL is no place to assume nothing sensitive
# travels. Its warnings and errors still come through.
_URL_LOGGER: Final = "httpx"
_URL_LOGGER_LEVEL: Final = logging.WARNING

_root_handlers_before: list[logging.Handler] | None = None
_root_level_before: int | None = None
_url_logger_level_before: int | None = None


class _LazyStdout:
    """A stdout proxy that resolves ``sys.stdout`` at write time, not at configuration time.

    Binding the stream object directly pins whatever ``sys.stdout`` was when logging was
    configured. Under pytest that is the capture object belonging to one test, and later
    writes then go to a stream the framework has already moved on from.
    """

    def write(self, message: str) -> int:
        return sys.stdout.write(message)

    def flush(self) -> None:
        sys.stdout.flush()


def register_secret(value: str | None) -> None:
    """Register a value to be scrubbed from every log event."""
    if value and len(value) >= _MIN_SECRET_LENGTH:
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


def trace_id_of(traceparent: str | None) -> str | None:
    """Extract the trace id from a ``traceparent``, or ``None`` if it is not one.

    Validates the shape rather than trusting it: the header is caller-supplied, it ends up in
    every log line for the request and in a response header, and the web client's own
    ``traceIdOf`` applies exactly this rule. Two implementations of one header format that
    disagree is how malformed data gets in through the more permissive side.

    It lives at the logging boundary rather than in the app because both the correlation
    middleware and the Host allow-list, which runs before it, bind the id it yields.

    :param traceparent: a header value, possibly absent or malformed.
    :returns: the 32-character trace id, or ``None`` when it cannot be parsed.
    """
    if not traceparent:
        return None
    fields = traceparent.split("-")
    if len(fields) < _TRACEPARENT_FIELDS:
        return None
    trace_id = fields[1]
    if not _TRACE_ID.match(trace_id) or trace_id == _INVALID_TRACE_ID:
        return None
    return trace_id


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


def _redact_value(value: Any, depth: int = 0) -> Any:
    """Redact recursively, so a secret nested in a payload is not missed."""
    if depth >= _MAX_REDACTION_DEPTH:
        return REDACTED
    if isinstance(value, str):
        return _scrub(value)
    if isinstance(value, dict):
        return {
            key: REDACTED if _is_secret_key(str(key)) else _redact_value(item, depth + 1)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        rebuilt = [_redact_value(item, depth + 1) for item in value]
        return tuple(rebuilt) if isinstance(value, tuple) else rebuilt
    return value


def redact_secrets(
    _logger: Any, _method_name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """structlog processor: remove secret material before anything is rendered.

    Must run *after* ``format_exc_info``. An exception is one of the likeliest carriers of a
    secret — a client that raises with the key in its message or URL — and until the traceback
    has been rendered into a string there is nothing here to scrub.

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

    Reads only the bound id. The middleware seeds that id *from* the active span where there is
    one, so this stays the single source: a caller quoting the id from a response header finds
    the same value in the logs.
    """
    correlation_id = _correlation_id.get()
    if correlation_id is not None:
        event_dict[CORRELATION_ID_FIELD] = correlation_id
    return event_dict


def log_request(scope: Scope, status: int, started: float) -> None:
    """Write the one line the assistant keeps for a request, at INFO whatever its status.

    Called before the correlation id is unbound, so the line carries it. Uvicorn's own access line
    is switched off because it is written later, from the protocol layer, with nothing bound. A
    failure worth acting on already has its own ERROR line from the problem handlers.

    :param scope: the request's ASGI scope. Its ``path`` never holds the query string, which is
        why the line is built from it: a URL is no place to assume nothing sensitive travels, and
        no amount is ever logged (ADR-028).
    :param status: the status the request was answered with.
    :param started: :func:`time.perf_counter` when the request arrived.
    """
    elapsed = time.perf_counter() - started
    _log.info(
        REQUEST_EVENT,
        method=scope["method"],
        path=scope["path"],
        status=status,
        duration_ms=round(elapsed * _MILLISECONDS_PER_SECOND, _DURATION_DECIMALS),
    )


def _processors() -> Iterable[structlog.typing.Processor]:
    """The shared chain, in the order the ordering matters for.

    ``format_exc_info`` renders the traceback into a string; ``redact_secrets`` follows so that
    string is scrubbed. Reversing these two silently leaks every secret an exception carries.
    """
    return (
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        add_correlation_id,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        redact_secrets,
    )


def configure_logging(settings: Settings) -> None:
    """Configure structlog to emit JSON on stdout, through the redaction boundary.

    Rebinds the stdlib root logger too, so a library that logs through ``logging`` is rendered
    in the same format instead of bypassing the boundary with its own. The one exception to the
    configured level is httpx, held at WARNING because it logs every request's full URL at INFO.
    """
    global _root_handlers_before, _root_level_before, _url_logger_level_before

    register_secret(settings.anthropic_api_key_value)

    stream: TextIO = _LazyStdout()  # type: ignore[assignment]
    shared = _processors()

    structlog.configure(
        processors=[*shared, structlog.processors.JSONRenderer()],
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelNamesMapping()[settings.log_level]
        ),
        logger_factory=structlog.PrintLoggerFactory(file=stream),
        cache_logger_on_first_use=True,
    )

    handler = logging.StreamHandler(stream=stream)
    handler.setFormatter(
        structlog.stdlib.ProcessorFormatter(
            processors=[
                structlog.stdlib.ProcessorFormatter.remove_processors_meta,
                structlog.processors.JSONRenderer(),
            ],
            foreign_pre_chain=list(shared),
        )
    )
    root = logging.getLogger()
    url_logger = logging.getLogger(_URL_LOGGER)
    if _root_handlers_before is None:
        _root_handlers_before = list(root.handlers)
        _root_level_before = root.level
        _url_logger_level_before = url_logger.level
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(settings.log_level)
    url_logger.setLevel(_URL_LOGGER_LEVEL)


def reset_logging() -> None:
    """Restore the root logger, and httpx's level, to what they were before ``configure_logging``.

    Both are process-wide state. Without this a test that configures logging leaves every later
    test writing through its handler, which is the sort of ordering dependence the test
    conventions rule out.
    """
    global _root_handlers_before, _root_level_before, _url_logger_level_before

    if _root_handlers_before is None:
        return
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    for previous in _root_handlers_before:
        root.addHandler(previous)
    if _root_level_before is not None:
        root.setLevel(_root_level_before)
    if _url_logger_level_before is not None:
        logging.getLogger(_URL_LOGGER).setLevel(_url_logger_level_before)
    _root_handlers_before = None
    _root_level_before = None
    _url_logger_level_before = None


def configure_tracing(settings: Settings) -> TracerProvider | None:
    """Install an OTLP tracer provider, or none at all.

    :returns: the provider that is actually installed, or ``None`` when export is disabled.
    :raises RuntimeError: if a different tracer provider is already installed.

    Returning ``None`` rather than installing a provider with no exporter is deliberate: it
    makes "telemetry is off" observable to a test, and it keeps the OpenTelemetry default no-op
    tracer in place, which costs nothing per span.

    The already-installed check exists because ``set_tracer_provider`` refuses to override and
    only logs a warning. Returning the provider we tried to install would hand the caller a
    handle to a pipeline that is not running.
    """
    if not settings.traces_enabled:
        return None

    installed = trace.get_tracer_provider()
    if isinstance(installed, TracerProvider):
        raise RuntimeError(
            "a TracerProvider is already installed; OpenTelemetry refuses to replace it, so "
            "configuring tracing twice in one process would leave spans going to the first one"
        )

    endpoint = f"{str(settings.otlp_endpoint).rstrip('/')}/v1/traces"
    provider = TracerProvider(resource=Resource.create({"service.name": settings.service_name}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    trace.set_tracer_provider(provider)
    return provider
