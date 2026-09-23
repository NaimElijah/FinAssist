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
:mod:`~finassist_assistant.adapters.problems`, a request whose ``Host`` names no allowed host is
refused before anything else runs, by :mod:`~finassist_assistant.adapters.hosts`, and every other
request needs a valid bearer token and a permission, checked by
:mod:`~finassist_assistant.adapters.auth` (ADR-030, ADR-032).

See ADR-019, ADR-021, ADR-026, ADR-028, ADR-030 and ADR-032.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import fields
from http import HTTPStatus
from typing import Final

import structlog
from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from finassist_assistant.adapters.auth import (
    ASSISTANT_AUDIENCE,
    BearerAuthentication,
    RequirePermission,
    TokenVerifier,
    jwks_key_source,
)
from finassist_assistant.adapters.hosts import HostAllowList
from finassist_assistant.adapters.observability import (
    TRACEPARENT_HEADER,
    configure_logging,
    configure_tracing,
    log_request,
    set_correlation_id,
    trace_id_of,
)
from finassist_assistant.adapters.openapi import document_problems, document_security
from finassist_assistant.adapters.problems import answer_unexpected, install_problem_handlers
from finassist_assistant.adapters.seed import SEED_SYMBOLS, seeded_assembly
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Assembly, assemble, utc_now
from finassist_assistant.portfolio.adapters.inbound.api.router import portfolio_router

CORRELATION_ID_HEADER: Final = "X-Correlation-Id"

PORTFOLIO_PREFIX: Final = "/api/v1/portfolio"

CONTRACT_TITLE: Final = "FinAssist assistant"

CONTRACT_VERSION: Final = "v1"
"""The contract's version, which is the one in the path, not the package's own version."""

_log = structlog.get_logger(__name__)


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


def create_app(
    settings: Settings | None = None,
    assembly: Assembly | None = None,
    *,
    token_verifier: TokenVerifier | None = None,
) -> FastAPI:
    """Build the application.

    :param settings: configuration to use. Defaults to reading the environment, which is what
        the entry point does; tests pass an explicit instance instead.
    :param assembly: the use cases and adapters to serve. Defaults to :func:`assemble` with the
        wall clock — or, when ``settings.dev_seed`` is set, to the development seed's own
        assembly — which is what the entry point does; a test passes one it has built itself,
        seeded or not, on a fixed clock or the wall clock, and then reaches the very instances
        the app holds.
    :param token_verifier: decides whether a bearer token is genuine (ADR-030). Defaults to a
        real :class:`~finassist_assistant.adapters.auth.TokenVerifier` built from *settings* and
        the wall clock, which is what the entry point does; a test passes one built against a
        stand-in key source and a fixed clock instead.
    :returns: a configured FastAPI application with logging and tracing already installed, and
        the assembly hung on ``app.state.assembly``.
    """
    resolved = settings if settings is not None else Settings()
    resolved_verifier = (
        token_verifier
        if token_verifier is not None
        else TokenVerifier(
            jwks_key_source(resolved.oidc_jwks_uri),
            resolved.oidc_issuer,
            ASSISTANT_AUDIENCE,
            utc_now,
        )
    )

    configure_logging(resolved)
    configure_tracing(resolved)

    app = FastAPI(
        title=CONTRACT_TITLE,
        version=CONTRACT_VERSION,
        # Fixed rather than taken from wherever the document is served, so the document generated
        # in a test is the one a running assistant serves. The ledger fixes its own to "/" too.
        servers=[{"url": "/"}],
        # No Swagger UI and no ReDoc: both pages load their UI from a CDN, and the ledger serves no
        # such UI either (ADR-028). The document itself is still served at /openapi.json.
        docs_url=None,
        redoc_url=None,
    )
    install_problem_handlers(app)
    document_problems(app)
    document_security(app)

    # The readiness probe deliberately does not consult the assembly: every adapter behind it is
    # in-memory, so a check against one would report health that no dependency had been asked about.
    served: Assembly
    if assembly is not None:
        served = assembly
    elif resolved.dev_seed:
        served = seeded_assembly()
        # No amount here, and none in SEED_SYMBOLS: names only, same as every other start-up field.
        _log.info("development seed loaded", symbols=list(SEED_SYMBOLS))
    else:
        served = assemble()
    app.state.assembly = served

    portfolio = served.portfolio
    app.include_router(
        portfolio_router(
            portfolio.view_position,
            portfolio.value_holding,
            portfolio.dispose_units,
            require=RequirePermission,
        ),
        prefix=PORTFOLIO_PREFIX,
    )

    # Added before the correlation middleware, so correlation wraps it and a refused request
    # still gets a correlation id and a request-answered line (ADR-019, ADR-030): the order is
    # HostAllowList -> correlate -> auth -> routes, and Starlette runs the last-added middleware
    # first, so the earliest-added of the three ends up closest to the routes.
    app.add_middleware(BearerAuthentication, verifier=resolved_verifier)

    @app.middleware("http")
    async def correlate(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """Bind a correlation id for the request, log it, and echo the id back on the response.

        An exception nothing inside has handled is answered here rather than left to Starlette's
        outermost handler. That one runs outside this middleware, after the id is unbound, so its
        log line could not be joined to the response, and the response would carry no id to join
        it with. The request line is written here for the same reason, before the id is unbound
        (ADR-019), and from a ``finally``: if choosing the id or answering a failure raises, no
        response is produced here and Starlette's last resort answers 500, which the line records.
        """
        started = time.perf_counter()
        status = int(HTTPStatus.INTERNAL_SERVER_ERROR)
        try:
            correlation_id = _correlation_id_for(request)
            set_correlation_id(correlation_id)
            try:
                response = await call_next(request)
            except Exception as unexpected:
                response = answer_unexpected(request, unexpected)
            status = response.status_code
        finally:
            log_request(request.scope, status, started)
            set_correlation_id(None)
        response.headers[CORRELATION_ID_HEADER] = correlation_id
        return response

    # Added after the correlation middleware, so it runs before it and before authentication too:
    # a refused request reaches no handler and no correlation header is echoed on it (ADR-030). It
    # writes the refusal's request line itself, with the trace id of the caller's traceparent.
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
