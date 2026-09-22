"""The portfolio context's HTTP routes: read a position, value a holding, price a disposal.

A factory that takes the use cases as arguments, rather than a module-level router that fetches
them, so this package never imports the composition root and the architecture tests can hold it to
that. Where the router is mounted is the composition root's decision too.

Handlers are ``def``, never ``async def``. Every port behind these use cases is synchronous
(ADR-021), and FastAPI runs a ``def`` handler in its thread pool; an ``async def`` handler calling a
blocking port would stall the event loop for every other request while it waited.

Pricing a disposal is a ``POST`` answered 200, although it records nothing. Its proceeds are an
amount of money, and money never travels in a URL: a URL is written to access logs, to trace
attributes and by any proxy it passes (ADR-028). Its body must be ``application/json``, as every
ledger ``POST``'s must be (ADR-030).

Every failure a use case raises is left to propagate. The composition root answers each one as a
problem (ADR-028), which is the only place that may see every context's exceptions.

Every route also declares a permission dependency, built by the ``require`` factory the
composition root passes in (ADR-030, ADR-032). This module never imports
``RequirePermission`` itself - that would be exactly the composition-root import the slice rule
forbids - so ``require`` is typed only as "a permission name in, a dependency out".
"""

from __future__ import annotations

from collections.abc import Callable
from decimal import Decimal
from http import HTTPStatus
from typing import Final

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.routing import APIRoute
from pydantic.alias_generators import to_camel

from finassist_assistant.portfolio.adapters.inbound.api.models import (
    DisposalRequestModel,
    DispositionModel,
    PositionModel,
    ValuationModel,
)
from finassist_assistant.portfolio.application.usecase import (
    DisposeUnits,
    ValueHolding,
    ViewPosition,
)
from finassist_assistant.portfolio.domain.model import Symbol

__all__ = ["READ_PORTFOLIO", "RequirePermissionFactory", "portfolio_router"]

_JSON_MEDIA_TYPE: Final = "application/json"
_NOT_JSON_DETAIL: Final = "The request body must be application/json."

READ_PORTFOLIO: Final = "portfolio:read"
"""This context's one permission (ADR-030's Vocabulary): every route here only ever reads.

Owned by this module, not by the composition root, which keeps its own copy in
``adapters.auth.ROLE_PERMISSIONS`` - a context may not import the composition root back, so the
one permission this context grants cannot be a constant shared across that boundary.
``tests/test_authorization.py`` pins both spellings equal.
"""

RequirePermissionFactory = Callable[[str], Callable[[Request], None]]
"""What ``require`` must be: a permission name in, a FastAPI dependency out.

Typed as a plain callable rather than by naming ``RequirePermission``, so this module still
never imports the composition root that defines it.
"""


def _operation_id(route: APIRoute) -> str:
    """Names an operation after its handler in camelCase, as the ledger's operations are named.

    FastAPI's default appends the path and the method, which is unique but reads badly in a client
    generated from the contract.
    """
    return to_camel(route.name)


def _require_json(request: Request) -> None:
    """Refuses a body that is not ``application/json`` with 415, before the body is validated.

    FastAPI does not refuse one on its own. Given a non-JSON or missing ``Content-Type``, it hands
    the raw bytes to validation, which fails as a 400. A route's dependencies run before that
    failure is raised, so the answer is the 415 a ledger ``POST`` gives instead. Parameters such as
    ``charset`` are allowed; any other media type, a ``+json`` one included, is refused.

    :raises HTTPException: 415, when the media type is anything but ``application/json``.
    """
    media_type = request.headers.get("content-type", "").partition(";")[0].strip().lower()
    if media_type != _JSON_MEDIA_TYPE:
        raise HTTPException(status_code=HTTPStatus.UNSUPPORTED_MEDIA_TYPE, detail=_NOT_JSON_DETAIL)


def portfolio_router(
    view: ViewPosition,
    value: ValueHolding,
    dispose: DisposeUnits,
    *,
    require: RequirePermissionFactory,
) -> APIRouter:
    """Build the routes over one holding.

    :param view: reads a position.
    :param value: values a holding at the latest price.
    :param dispose: prices selling units, oldest lot first.
    :param require: builds the permission dependency every route below declares exactly once
        (ADR-030, ADR-032). The composition root passes its own ``RequirePermission``.
    :returns: a router whose paths begin ``/holdings/{symbol}``. The caller chooses the prefix.
    """
    router = APIRouter(generate_unique_id_function=_operation_id)

    @router.get("/holdings/{symbol}/position", dependencies=[Depends(require(READ_PORTFOLIO))])
    def read_position(symbol: str) -> PositionModel:
        """What is held in one instrument, and what it cost in total."""
        return PositionModel.of(view.of(Symbol(symbol)))

    @router.get("/holdings/{symbol}/valuation", dependencies=[Depends(require(READ_PORTFOLIO))])
    def value_holding(symbol: str) -> ValuationModel:
        """What a holding is worth at the latest price, and the gain that is not yet realised."""
        return ValuationModel.of(value.of(Symbol(symbol)))

    @router.post(
        "/holdings/{symbol}/disposal",
        # 200, not 201: pricing creates nothing.
        status_code=HTTPStatus.OK,
        # The permission dependency comes first, so a caller with no permission sending a
        # non-JSON body is answered 403, never 415 - refusing who may ask before refusing how
        # they asked. FastAPI evaluates a route's dependencies in this declared order.
        dependencies=[Depends(require(READ_PORTFOLIO)), Depends(_require_json)],
    )
    def price_disposal(symbol: str, disposal: DisposalRequestModel) -> DispositionModel:
        """What selling a quantity of units for the given proceeds would realise, oldest lot first.

        A calculation that records nothing: no lot is consumed, so it is safe to repeat. It is a
        POST only so that the proceeds, an amount of money, never enter a URL. Pricing a
        disposal still needs ``portfolio:read``, and nothing more: it records nothing, so no
        write permission exists for it to need.
        """
        held = Symbol(symbol)
        units = Decimal(disposal.quantity)
        priced = dispose.of(held, units, disposal.proceeds.to_money())
        return DispositionModel.of(held, units, priced)

    return router
