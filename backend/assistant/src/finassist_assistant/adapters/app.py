"""The assistant's HTTP entry point.

It serves health and readiness and nothing else. ADR-004 — the wire contract every business
endpoint must be described by — is still Proposed, so adding one here would pre-empt a decision
that is deliberately open.

The use cases *are* assembled, by :func:`~finassist_assistant.adapters.wiring.assemble`, and hung
on ``app.state.assembly``. Nothing routes to them yet. Building them here means the wiring is
exercised on every start rather than the first time somebody writes a route, which is when a
constructor mismatch would otherwise be found.

See ADR-019 and ADR-021.
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import fields
from typing import Final

import structlog
from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from finassist_assistant.adapters.observability import (
    TRACEPARENT_HEADER,
    configure_logging,
    configure_tracing,
    set_correlation_id,
)
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble

CORRELATION_ID_HEADER: Final = "X-Correlation-Id"

# A W3C trace id is 32 lowercase hex characters, and all-zero is explicitly invalid.
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")
_INVALID_TRACE_ID: Final = "0" * 32
_TRACEPARENT_FIELDS: Final = 4

_log = structlog.get_logger(__name__)


def trace_id_of(traceparent: str | None) -> str | None:
    """Extract the trace id from a ``traceparent``, or ``None`` if it is not one.

    Validates the shape rather than trusting it: the header is caller-supplied, it ends up in
    every log line for the request and in a response header, and the web client's own
    ``traceIdOf`` applies exactly this rule. Two implementations of one header format that
    disagree is how malformed data gets in through the more permissive side.

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


def _correlation_id_for(request: Request) -> str:
    """The single correlation id for this request, used for both the logs and the header.

    Precedence matters. Where OpenTelemetry has started a span, its trace id wins, because that
    is what the collector will record and a log line quoting anything else could not be joined
    to it. Otherwise the caller's ``traceparent`` continues, and failing that a new id begins.

    Deriving both the response header and the log field from this one function is the point: an
    earlier version minted a fresh uuid for the header while the log lines carried the span's
    trace id, so the id a caller was handed matched nothing in the logs.
    """
    span_context = trace.get_current_span().get_span_context()
    if span_context.is_valid:
        return format(span_context.trace_id, "032x")
    return trace_id_of(request.headers.get(TRACEPARENT_HEADER)) or uuid.uuid4().hex


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the application.

    :param settings: configuration to use. Defaults to reading the environment, which is what
        the entry point does; tests pass an explicit instance instead.
    :returns: a configured FastAPI application with logging and tracing already installed.
    """
    resolved = settings if settings is not None else Settings()

    configure_logging(resolved)
    configure_tracing(resolved)

    app = FastAPI(title="FinAssist assistant", version="0.1.0")

    # Assembled now, called by nothing yet. The readiness probe deliberately does not consult it:
    # every adapter behind it is in-memory, so a check against one would report health that no
    # dependency had been asked about.
    assembly: Assembly = assemble()
    app.state.assembly = assembly

    @app.middleware("http")
    async def correlate(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Bind a correlation id for the request and echo it back on the response."""
        correlation_id = _correlation_id_for(request)
        set_correlation_id(correlation_id)
        try:
            response = await call_next(request)
        finally:
            set_correlation_id(None)
        response.headers[CORRELATION_ID_HEADER] = correlation_id
        return response

    @app.get("/health")
    async def health() -> JSONResponse:
        """Liveness: the process is up and serving. Checks no dependency on purpose."""
        return JSONResponse({"status": "UP"})

    @app.get("/ready")
    async def ready() -> JSONResponse:
        """Readiness: the assistant is willing to take traffic.

        Identical to liveness today because the assistant depends on nothing yet. It becomes a
        real check when the ledger client exists, and it is separate now so that change is an
        edit to one function rather than a new endpoint and a new probe.
        """
        return JSONResponse({"status": "UP"})

    if resolved.traces_enabled:
        FastAPIInstrumentor.instrument_app(app)

    # No correlation id here on purpose: startup happens outside any request, and the
    # processor adds the field only when one is bound.
    _log.info(
        "assistant started",
        service=resolved.service_name,
        traces_enabled=resolved.traces_enabled,
        use_cases=len(fields(assembly.portfolio)),
    )
    return app
