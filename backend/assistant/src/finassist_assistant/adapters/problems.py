"""Every failure the assistant answers, as an RFC 9457 problem with a stable ``code`` (ADR-028).

It lives in the composition root because mapping every context's failures means importing every
context's exceptions, and this is the one package allowed to see them all. No context imports it.

``title`` and ``detail`` are prose for a person and may be reworded. ``code`` is what a client
branches on, so once served it is part of the contract. Where a meaning overlaps the ledger's, the
string is the ledger's (``Problems.java``): the web client parses both services' problems alike
(ADR-026), and one meaning spelled two ways is one meaning it would have to handle twice.

Starlette picks a handler by walking the raised exception's class hierarchy and taking the first
class registered, so a subclass's entry wins over its parent's. Three entries rely on that.
``CurrencyMismatchError`` and ``InsufficientHoldingError`` are ``ValueError`` subclasses with codes
of their own, and so is pydantic's ``ValidationError``: without its own entry, a model the
assistant built badly would be answered 422, as though the caller had broken a rule.

Starlette also puts a handler for ``Exception`` on its outermost middleware, outside the
correlation middleware in ``app.py``, where the correlation id is already unbound. That middleware
therefore answers an unexpected exception itself, through :func:`answer_unexpected`, and the
registration here is only the last resort for a failure outside it.
"""

from __future__ import annotations

import traceback
from collections.abc import Callable, Mapping, Sequence
from http import HTTPStatus
from typing import Any, Final

import structlog
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError, ValidationException
from pydantic import ValidationError
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import JSONResponse

from finassist_assistant.portfolio.application.port import (
    HoldingNotFoundError,
    PriceUnavailableError,
)
from finassist_assistant.portfolio.domain.analysis import InsufficientHoldingError
from finassist_assistant.shared import CurrencyMismatchError

__all__ = [
    "BLANK_TYPE",
    "CURRENCY_MISMATCH",
    "HOLDING_NOT_FOUND",
    "HOST_NOT_ALLOWED",
    "INSUFFICIENT_HOLDING",
    "INTERNAL_ERROR",
    "MALFORMED_REQUEST",
    "PRICE_UNAVAILABLE",
    "PROBLEM_MEDIA_TYPE",
    "RULE_VIOLATED",
    "answer_unexpected",
    "install_problem_handlers",
    "problem_response",
]

PROBLEM_MEDIA_TYPE: Final = "application/problem+json"
"""RFC 9457's media type, which tells a client the body is a problem before it parses one."""

BLANK_TYPE: Final = "about:blank"
"""The ``type`` of a problem with no page of its own.

RFC 9457 reads an absent ``type`` as this. It is written out, as the ledger writes it, so a client
reading the member finds it rather than having to know the RFC's default.
"""

HOST_NOT_ALLOWED: Final = "HOST_NOT_ALLOWED"
"""The ``Host`` header names no host on the allow-list (ADR-027)."""

MALFORMED_REQUEST: Final = "MALFORMED_REQUEST"
"""A parameter or body the request could not be read from: fix the request."""

RULE_VIOLATED: Final = "RULE_VIOLATED"
"""Well-formed, and refused by a rule the request broke."""

CURRENCY_MISMATCH: Final = "CURRENCY_MISMATCH"
"""The request would combine amounts in two currencies, which nothing converts (ADR-012)."""

INTERNAL_ERROR: Final = "INTERNAL_ERROR"
"""The assistant failed. Nothing about the request explains it, and the detail says nothing more."""

HOLDING_NOT_FOUND: Final = "HOLDING_NOT_FOUND"
"""Nothing is held for the symbol in the path."""

INSUFFICIENT_HOLDING: Final = "INSUFFICIENT_HOLDING"
"""A disposal asks for more units than are held."""

PRICE_UNAVAILABLE: Final = "PRICE_UNAVAILABLE"
"""No price recent enough to value the holding exists. Try again later."""

_INTERNAL_ERROR_DETAIL: Final = "The assistant failed to handle this request."
_PRICE_UNAVAILABLE_DETAIL: Final = "No price recent enough to value this holding is available."
_MALFORMED_REQUEST_DETAIL: Final = "The request could not be read; each entry in errors names why."

# Where FastAPI says an input arrived. The first element of an error's location, and not part of
# the field's name.
_REQUEST_PARTS: Final = frozenset({"path", "query", "header", "cookie", "body"})

_log = structlog.get_logger(__name__)


def problem_response(
    status: HTTPStatus,
    code: str,
    detail: str,
    *,
    instance: str | None = None,
    errors: Sequence[dict[str, str]] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    """Build one problem. The only place its shape is decided.

    :param status: the HTTP status, copied into the body as RFC 9457 asks, and whose standard
        phrase is the ``title``.
    :param code: the stable code a client branches on.
    :param detail: prose about this occurrence. Never an exception's text on a 5xx.
    :param instance: the request path, which the ledger sends too. Left out where no handler ran,
        as on a Host refusal, exactly as the ledger leaves it out.
    :param errors: one ``{field, message}`` entry per rejected input, for a malformed request.
    :param headers: headers the failure carries, such as ``Allow`` on a 405.
    :returns: an ``application/problem+json`` response.
    """
    body: dict[str, Any] = {
        "type": BLANK_TYPE,
        "title": status.phrase,
        "status": int(status),
        "detail": detail,
    }
    if instance is not None:
        body["instance"] = instance
    body["code"] = code
    if errors is not None:
        body["errors"] = list(errors)
    return JSONResponse(body, status_code=status, headers=headers, media_type=PROBLEM_MEDIA_TYPE)


def answer_unexpected(request: Request, exception: Exception) -> JSONResponse:
    """Answer an exception nothing else claimed: 500, with nothing of the exception on the wire.

    Logged at ERROR through the redaction boundary. The line carries the correlation id that the
    response hands the caller, which is how the withheld cause is found.

    :param request: the request that failed.
    :param exception: what it failed with.
    :returns: a 500 ``INTERNAL_ERROR`` problem with a generic detail.
    """
    return _withheld(request, exception, HTTPStatus.INTERNAL_SERVER_ERROR)


def install_problem_handlers(app: FastAPI) -> None:
    """Register a handler for every failure the assistant answers, each producing a problem.

    :param app: the application to register them on, before it serves its first request; Starlette
        builds its middleware stack, handlers included, on that request.
    """
    handlers: Mapping[type[Exception], Callable[..., JSONResponse]] = {
        HoldingNotFoundError: _refusal(HTTPStatus.NOT_FOUND, HOLDING_NOT_FOUND),
        InsufficientHoldingError: _refusal(HTTPStatus.UNPROCESSABLE_CONTENT, INSUFFICIENT_HOLDING),
        CurrencyMismatchError: _refusal(HTTPStatus.UNPROCESSABLE_CONTENT, CURRENCY_MISMATCH),
        ValueError: _refusal(HTTPStatus.UNPROCESSABLE_CONTENT, RULE_VIOLATED),
        PriceUnavailableError: _price_unavailable,
        ValidationError: answer_unexpected,
        RequestValidationError: _malformed_request,
        HTTPException: _framework_failure,
        Exception: answer_unexpected,
    }
    for exception_class, handler in handlers.items():
        app.add_exception_handler(exception_class, handler)


def _refusal(status: HTTPStatus, code: str) -> Callable[[Request, Exception], JSONResponse]:
    """A handler for a 4xx whose exception message is the detail, because it says what was wrong.

    Safe for these exceptions only: each is raised by the domain about what the caller asked for,
    and its message names nothing the caller did not send.
    """

    def refuse(request: Request, exception: Exception) -> JSONResponse:
        return problem_response(status, code, str(exception), instance=request.url.path)

    return refuse


def _price_unavailable(request: Request, exception: PriceUnavailableError) -> JSONResponse:
    """503: the holding exists and cannot be valued now.

    Degraded rather than broken, so WARNING rather than ERROR. The detail is fixed, not the
    exception's message, because no 5xx carries exception text.

    Logged as the event and the symbol, with no traceback. While a feed is stale every valuation
    lands here, and a traceback per request would bury the one line that says which instrument
    has no price; the cause is the feed's staleness, not a stack.
    """
    _log.warning("no usable price; answered 503", symbol=str(exception.symbol))
    return problem_response(
        HTTPStatus.SERVICE_UNAVAILABLE,
        PRICE_UNAVAILABLE,
        _PRICE_UNAVAILABLE_DETAIL,
        instance=request.url.path,
    )


def _malformed_request(request: Request, exception: RequestValidationError) -> JSONResponse:
    """400, listing each rejected input by name and reason.

    Each error's ``input`` is deliberately not echoed: it is whatever the caller sent, and a
    problem body is no place to reflect it back.
    """
    errors = [
        {"field": _field_of(error["loc"]), "message": str(error["msg"])}
        for error in exception.errors()
    ]
    return problem_response(
        HTTPStatus.BAD_REQUEST,
        MALFORMED_REQUEST,
        _MALFORMED_REQUEST_DETAIL,
        instance=request.url.path,
        errors=errors,
    )


def _framework_failure(request: Request, exception: HTTPException) -> JSONResponse:
    """A failure Starlette raised on its own, such as an unknown route or an unsupported method.

    Coded from the status, as the ledger codes Spring's. 400 is ``MALFORMED_REQUEST``; any other
    4xx is its standard reason, which for 404, 405, 406 and 415 is exactly the ledger's code. A
    5xx is withheld like any other.
    """
    status = HTTPStatus(exception.status_code)
    if status.is_server_error:
        return _withheld(request, exception, status)
    code = MALFORMED_REQUEST if status is HTTPStatus.BAD_REQUEST else status.name
    return problem_response(
        status, code, exception.detail, instance=request.url.path, headers=exception.headers
    )


def _withheld(request: Request, exception: Exception, status: HTTPStatus) -> JSONResponse:
    """A 5xx: logged at ERROR, and answered with nothing of its cause (ADR-028).

    An exception's message is among the likeliest carriers of internals and data, and an
    ``HTTPException``'s detail is no different.

    A validation error is logged without its text at all. pydantic's and FastAPI's both quote the
    input they rejected, and the input a response model rejects is the owner's money, which is
    never logged. FastAPI's quotes it however the model is configured. Where validation failed, by
    which rule, and the stack that reached it are enough to find the bug.
    """
    if isinstance(exception, ValidationError | ValidationException):
        _log.error(
            "answered with a generic detail",
            status=int(status),
            exception_type=type(exception).__name__,
            errors=_rejections(exception),
            stack="".join(traceback.format_tb(exception.__traceback__)),
        )
    else:
        _log.error("answered with a generic detail", status=int(status), exc_info=exception)
    return problem_response(
        status, INTERNAL_ERROR, _INTERNAL_ERROR_DETAIL, instance=request.url.path
    )


def _rejections(exception: ValidationError | ValidationException) -> list[dict[str, str]]:
    """Each validation failure's location and error type, and never its input or its message.

    The message is left out too: a validator's own ``ValueError`` becomes it, and that text can
    quote the value it refused.
    """
    return [
        {"loc": ".".join(str(part) for part in error["loc"]), "type": str(error["type"])}
        for error in exception.errors()
    ]


def _field_of(location: Sequence[str | int]) -> str:
    """The dotted path to a rejected input, without the part of the request it arrived in.

    FastAPI locates ``?quantity=`` as ``("query", "quantity")``. The client knows it sent a query
    parameter, and wants the name.
    """
    named = location[1:] if len(location) > 1 and location[0] in _REQUEST_PARTS else location
    return ".".join(str(part) for part in named)
