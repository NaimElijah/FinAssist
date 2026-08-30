"""The assistant's HTTP entry point.

It serves health and readiness and nothing else. The assistant has no use case to expose yet,
and ADR-004 — the wire contract every business endpoint must be described by — is still
Proposed, so adding one here would pre-empt a decision that is deliberately open.

See ADR-019.
"""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable
from typing import Final

import structlog
from fastapi import FastAPI
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

CORRELATION_ID_HEADER: Final = "X-Correlation-Id"

_log = structlog.get_logger(__name__)


def _correlation_id_from(request: Request) -> str:
    """Continue the caller's trace where it sent one, otherwise start a new id.

    Only the trace-id field of ``traceparent`` is read. Parsing the whole W3C header here would
    duplicate what the OpenTelemetry propagator already does for the span; this exists so a log
    line is still correlated when tracing is switched off.
    """
    traceparent = request.headers.get(TRACEPARENT_HEADER)
    if traceparent:
        fields = traceparent.split("-")
        if len(fields) >= 3 and len(fields[1]) == 32:
            return fields[1]
    return uuid.uuid4().hex


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

    @app.middleware("http")
    async def correlate(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Bind a correlation id for the request and echo it back on the response."""
        correlation_id = _correlation_id_from(request)
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
    )
    return app
