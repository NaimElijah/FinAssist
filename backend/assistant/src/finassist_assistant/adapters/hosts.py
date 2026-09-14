"""The Host allow-list: ADR-027's defence against DNS rebinding.

Binding to loopback does not keep out a hostile page in the owner's own browser. DNS rebinding
repoints the page's own name at ``127.0.0.1`` after it has loaded, so the browser treats the
assistant as same-origin with the page and lets it read every response. What the page cannot
change is the name it was loaded from, which every request it makes still carries in ``Host``.
"""

from __future__ import annotations

from collections.abc import Sequence
from http import HTTPStatus
from typing import Final

from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from finassist_assistant.adapters.problems import HOST_NOT_ALLOWED, problem_response

__all__ = ["HostAllowList"]

# Namespaced, as ASGI asks of anything an application adds to a scope.
_ADMITTED: Final = "finassist.host_admitted"

_REFUSAL_DETAIL: Final = "The request's Host header does not name a host this assistant serves."


async def _record_admission(scope: Scope, receive: Receive, send: Send) -> None:
    """Stands in for the application behind the stock check, and only records being reached."""
    scope[_ADMITTED] = True


async def _discard(message: Message) -> None:
    """Swallows whatever the stock check writes, which is only ever its own refusal."""


class HostAllowList:
    """Refuses a request whose ``Host`` header names no allowed host, as the ledger refuses it.

    The decision is Starlette's ``TrustedHostMiddleware``, which ADR-027 names. Only its refusal
    is replaced. It answers 400 in plain text, and the ledger answers 403 with a
    ``HOST_NOT_ALLOWED`` problem (ADR-028); one client handles both services (ADR-026), so the
    refusal has to be the ledger's.

    The stock middleware writes its refusal rather than raising one, so there is nothing to catch.
    It runs instead in front of a stand-in application that records being reached, writing into a
    ``send`` that discards everything; the request is then passed on or refused here. The body
    never echoes the header back.

    Two differences from the ledger's filter come with the stock matching, which compares the text
    before the header's first colon exactly. An IPv6 literal such as ``[::1]`` can never be
    allowed, and case is significant in the header. Browsers send host names in lower case, and
    ``Settings`` lower-cases the allowed names.

    :param app: the application behind the check.
    :param allowed_hosts: the names to admit, as ``Settings.allowed_hosts`` has validated them.
    """

    def __init__(self, app: ASGIApp, allowed_hosts: Sequence[str]) -> None:
        self._app = app
        self._check = TrustedHostMiddleware(
            _record_admission, allowed_hosts=allowed_hosts, www_redirect=False
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        await self._check(scope, receive, _discard)
        if scope.pop(_ADMITTED, False):
            await self._app(scope, receive, send)
            return
        refusal = problem_response(HTTPStatus.FORBIDDEN, HOST_NOT_ALLOWED, _REFUSAL_DETAIL)
        await refusal(scope, receive, send)
