"""The assistant's HTTP entry point.

It serves the portfolio context's routes under ``/api/v1/portfolio``, which the web client's
portfolio screens call (ADR-026), and the health and readiness probes. The probes are left out of
the OpenAPI document: they are for whatever runs the process, not for a client generated from the
contract (ADR-028).

That document is committed as ``contracts/assistant-v1.openapi.json`` and pinned by
``tests/test_contract.py``. :mod:`~finassist_assistant.adapters.openapi` adjusts what FastAPI
generates so that it describes every failure as the problem the assistant actually sends.

The use cases are assembled by :func:`~finassist_assistant.adapters.wiring.assemble`, hung on
``app.state.assembly``, and handed to each router as arguments. A router never imports this
package, and the architecture tests fail the build if one does.

Every failure is answered as an RFC 9457 problem, by the handlers in
:mod:`~finassist_assistant.adapters.problems`, and a request whose ``Host`` names no allowed host
is refused before anything else runs, by :mod:`~finassist_assistant.adapters.hosts`.

See ADR-019, ADR-021, ADR-026, ADR-027 and ADR-028.
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

from finassist_assistant.adapters.hosts import HostAllowList
from finassist_assistant.adapters.observability import (
    TRACEPARENT_HEADER,
    configure_logging,
    configure_tracing,
    set_correlation_id,
)
from finassist_assistant.adapters.openapi import document_problems
from finassist_assistant.adapters.problems import answer_unexpected, install_problem_handlers
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble
from finassist_assistant.portfolio.adapters.inbound.api.router import portfolio_router

CORRELATION_ID_HEADER: Final = "X-Correlation-Id"

PORTFOLIO_PREFIX: Final = "/api/v1/portfolio"

CONTRACT_TITLE: Final = "FinAssist assistant"

CONTRACT_VERSION: Final = "v1"
"""The contract's version, which is the one in the path, not the package's own version."""

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


def create_app(settings: Settings | None = None, assembly: Assembly | None = None) -> FastAPI:
    """Build the application.

    :param settings: configuration to use. Defaults to reading the environment, which is what
        the entry point does; tests pass an explicit instance instead.
    :param assembly: the use cases and adapters to serve. Defaults to :func:`assemble` with the
        wall clock, which is what the entry point does; a test passes one it has seeded, or one
        built on a fixed clock, and then reaches the very instances the app holds.
    :returns: a configured FastAPI application with logging and tracing already installed, and
        the assembly hung on ``app.state.assembly``.
    """
    resolved = settings if settings is not None else Settings()

    configure_logging(resolved)
    configure_tracing(resolved)

    app = FastAPI(
        title=CONTRACT_TITLE,
        version=CONTRACT_VERSION,
        # Fixed rather than taken from wherever the document is served, so the document generated
        # in a test is the one a running assistant serves. The ledger fixes its own to "/" too.
        servers=[{"url": "/"}],
    )
    install_problem_handlers(app)
    document_problems(app)

    # The readiness probe deliberately does not consult the assembly: every adapter behind it is
    # in-memory, so a check against one would report health that no dependency had been asked about.
    served: Assembly = assembly if assembly is not None else assemble()
    app.state.assembly = served

    portfolio = served.portfolio
    app.include_router(
        portfolio_router(portfolio.view_position, portfolio.value_holding, portfolio.dispose_units),
        prefix=PORTFOLIO_PREFIX,
    )

    @app.middleware("http")
    async def correlate(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Bind a correlation id for the request and echo it back on the response.

        An exception nothing inside has handled is answered here rather than left to Starlette's
        outermost handler. That one runs outside this middleware, after the id is unbound, so its
        log line could not be joined to the response, and the response would carry no id to join
        it with.
        """
        correlation_id = _correlation_id_for(request)
        set_correlation_id(correlation_id)
        try:
            response = await call_next(request)
        except Exception as unexpected:
            response = answer_unexpected(request, unexpected)
        finally:
            set_correlation_id(None)
        response.headers[CORRELATION_ID_HEADER] = correlation_id
        return response

    # Added after the correlation middleware, so it runs before it: a refused request reaches no
    # handler and is given no correlation id (ADR-027).
    app.add_middleware(HostAllowList, allowed_hosts=resolved.allowed_hosts)

    @app.get("/health", include_in_schema=False)
    async def health() -> JSONResponse:
        """Liveness: the process is up and serving. Checks no dependency on purpose."""
        return JSONResponse({"status": "UP"})

    @app.get("/ready", include_in_schema=False)
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
        use_cases=len(fields(served.portfolio)),
    )
    return app
