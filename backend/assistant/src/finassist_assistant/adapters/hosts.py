"""The Host allow-list: ADR-030's defence against DNS rebinding.

Binding to loopback does not keep out a hostile page in the owner's own browser. DNS rebinding
repoints the page's own name at ``127.0.0.1`` after it has loaded, so the browser treats the
assistant as same-origin with the page and lets it read every response. What the page cannot
change is the name it was loaded from, which every request it makes still carries in ``Host``.
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from http import HTTPStatus
from typing import Final

from starlette.datastructures import Headers
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from finassist_assistant.adapters.observability import (
    TRACEPARENT_HEADER,
    log_request,
    set_correlation_id,
    trace_id_of,
)
from finassist_assistant.adapters.problems import HOST_NOT_ALLOWED, problem_response

__all__ = ["HostAllowList"]

# Namespaced, as ASGI asks of anything an application adds to a scope.
_ADMITTED: Final = "finassist.host_admitted"

_REFUSAL_DETAIL: Final = "The request's Host header does not name a host this assistant serves."

_HTTP_SCOPE: Final = "http"


async def _record_admission(scope: Scope, receive: Receive, send: Send) -> None:
    """Stands in for the application behind the stock check, and only records being reached."""
    scope[_ADMITTED] = True


async def _discard(message: Message) -> None:
    """Swallows whatever the stock check writes, which is only ever its own refusal."""


class HostAllowList:
    """Refuses a request whose ``Host`` header names no allowed host, as the ledger refuses it.

    The decision is Starlette's ``TrustedHostMiddleware``, which ADR-030 names. Only its refusal
    is replaced. It answers 400 in plain text, and the ledger answers 403 with a
    ``HOST_NOT_ALLOWED`` problem (ADR-028); one client handles both services (ADR-026), so the
    refusal has to be the ledger's.

    The stock middleware writes its refusal rather than raising one, so there is nothing to catch.
    It runs instead in front of a stand-in application that records being reached, writing into a
    ``send`` that discards everything; the request is then passed on or refused here. The body
    never echoes the header back.

    A refused request never reaches the correlation middleware, so its request line is written
    here, as every admitted request has one written there. It carries the trace id of the caller's
    ``traceparent`` when that is valid (ADR-019): a UI loaded under a name the list does not admit
    sees an outage, and that id is what joins the web client's log of the 403 to this line. No
    fresh id is minted, because the refusal echoes no correlation header a caller could quote. A
    refused WebSocket upgrade writes none. Its scope carries no method, and the request line records
    an HTTP request: no WebSocket route is served, so an admitted upgrade writes none either.
    uvicorn reports the refused handshake itself, at INFO on ``uvicorn.error``, which
    ``access_log=False`` does not switch off. That line carries the upgrade's path with its query
    string. It is acceptable because no route reads a query string and no amount travels in a URL
    (ADR-028), so the only text it can quote is text the upgrader chose to send.

    Two differences from the ledger's filter come with the stock matching, which compares the text
    before the header's first colon exactly. An IPv6 literal such as ``[::1]`` can never be
    allowed, so ``Settings`` refuses an entry with a colon or a bracket rather than accept one that
    matches nothing. And case is significant in the header: browsers send host names in lower
    case, and ``Settings`` lower-cases the allowed names.

    :param app: the application behind the check.
    :param allowed_hosts: the names to admit, as ``Settings.allowed_hosts`` has validated them.
    """

    def __init__(self, app: ASGIApp, allowed_hosts: Sequence[str]) -> None:
        self._app = app
        self._check = TrustedHostMiddleware(
            _record_admission, allowed_hosts=allowed_hosts, www_redirect=False
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        started = time.perf_counter()
        await self._check(scope, receive, _discard)
        if scope.pop(_ADMITTED, False):
            await self._app(scope, receive, send)
            return
        refusal = problem_response(HTTPStatus.FORBIDDEN, HOST_NOT_ALLOWED, _REFUSAL_DETAIL)
        if scope["type"] != _HTTP_SCOPE:
            await refusal(scope, receive, send)
            return
        set_correlation_id(trace_id_of(Headers(scope=scope).get(TRACEPARENT_HEADER)))
        try:
            await refusal(scope, receive, send)
        finally:
            # Written even if sending the refusal fails, such as to a caller already gone.
            log_request(scope, refusal.status_code, started)
            set_correlation_id(None)
