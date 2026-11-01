"""The request-body bound: no request makes the assistant buffer a body of any size.

FastAPI reads and parses a route's whole body before any of its dependencies runs, the
permission check included, and nothing else in the stack bounds it. :class:`RequestBodyLimit`
refuses a body over :data:`MAX_REQUEST_BODY_BYTES`, so no route buffers more than that. A body
that declares its length is refused before authentication, and before it is read. A body that
declares none, such as a chunked one, is counted as the route reads it, which is after
authentication: an unauthenticated one is answered 401 and never read.
"""

from __future__ import annotations

from http import HTTPStatus
from typing import Final

import structlog
from starlette.datastructures import Headers
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from finassist_assistant.adapters.problems import (
    CONTENT_TOO_LARGE,
    CONTENT_TOO_LARGE_DETAIL,
    problem_response,
)

__all__ = ["MAX_REQUEST_BODY_BYTES", "RequestBodyLimit"]

MAX_REQUEST_BODY_BYTES: Final = 64 * 1024
"""The largest request body accepted, in bytes.

The ledger and the gateway hold the same limit, and the ledger answers it with the same
``CONTENT_TOO_LARGE`` code, so one client reads every service's refusal alike (ADR-026); ADR-058
records the agreement. The one body served today, a disposal, is a few hundred bytes, so the
bound leaves room without leaving it unbounded.
"""

_HTTP_SCOPE: Final = "http"
_BODY_MESSAGE: Final = "http.request"

# The ledger's RequestBodyLimitFilter warning, so the refusal is visible at WARNING on both
# services; the request line beside it is INFO whatever its status.
_REFUSED_EVENT: Final = "refused a request whose body is larger than the limit"

_log = structlog.get_logger(__name__)


def _declared_length(scope: Scope) -> int | None:
    """The body length the request declares, or ``None`` when it declares none it can be held to.

    A value that is not a number is left to the count rather than trusted or refused here: the
    server in front has already refused a malformed ``Content-Length``, and whatever does arrive
    is counted either way.
    """
    declared = Headers(scope=scope).get("content-length")
    if declared is None or not (declared.isascii() and declared.isdigit()):
        return None
    return int(declared)


class RequestBodyLimit:
    """Refuses a request body over the limit with 413 ``CONTENT_TOO_LARGE`` (ADR-028's shape).

    A pure ASGI middleware, like :class:`~finassist_assistant.adapters.hosts.HostAllowList`. A
    declared ``Content-Length`` over the limit is refused before anything reads the body. A body
    that declares no length, such as a chunked one, is counted as it is read, and the read
    that crosses the limit raises a 413 ``HTTPException`` in place of the body; the route reading
    it never sees the excess, and the problem handlers answer it with the same problem.

    Starlette's own ``RequestBodyLimitMiddleware`` is not used: it refuses in plain text, and it
    checks a declared length only once the application reads the body or answers, so the request
    would be authenticated first.

    :param app: the application behind the check.
    :param max_bytes: the largest body accepted.
    """

    def __init__(self, app: ASGIApp, max_bytes: int = MAX_REQUEST_BODY_BYTES) -> None:
        self._app = app
        self._max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != _HTTP_SCOPE:
            await self._app(scope, receive, send)
            return

        declared = _declared_length(scope)
        if declared is not None and declared > self._max_bytes:
            _log.warning(_REFUSED_EVENT, limit_bytes=self._max_bytes)
            refusal = problem_response(
                HTTPStatus.CONTENT_TOO_LARGE,
                CONTENT_TOO_LARGE,
                CONTENT_TOO_LARGE_DETAIL,
                instance=Request(scope).url.path,
            )
            await refusal(scope, receive, send)
            return

        received = 0

        async def counted() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == _BODY_MESSAGE:
                received += len(message.get("body", b""))
                if received > self._max_bytes:
                    _log.warning(_REFUSED_EVENT, limit_bytes=self._max_bytes)
                    raise HTTPException(
                        status_code=HTTPStatus.CONTENT_TOO_LARGE,
                        detail=CONTENT_TOO_LARGE_DETAIL,
                    )
            return message

        await self._app(scope, counted, send)
