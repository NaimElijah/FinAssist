"""The portfolio context's HTTP routes: read a position, value a holding, price a disposal.

A factory that takes the use cases as arguments, rather than a module-level router that fetches
them, so this package never imports the composition root and the architecture tests can hold it to
that. Where the router is mounted is the composition root's decision too.

Handlers are ``def``, never ``async def``. Every port behind these use cases is synchronous
(ADR-021), and FastAPI runs a ``def`` handler in its thread pool; an ``async def`` handler calling a
blocking port would stall the event loop for every other request while it waited.

Every failure a use case raises is left to propagate. The composition root answers each one as a
problem (ADR-028), which is the only place that may see every context's exceptions.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Query
from fastapi.routing import APIRoute
from pydantic.alias_generators import to_camel

from finassist_assistant.portfolio.adapters.inbound.api.models import (
    DECIMAL_PATTERN,
    CurrencyCode,
    DispositionModel,
    PositionModel,
    ValuationModel,
    currency_for,
)
from finassist_assistant.portfolio.application.usecase import (
    DisposeUnits,
    ValueHolding,
    ViewPosition,
)
from finassist_assistant.portfolio.domain.model import Symbol
from finassist_assistant.shared import Money

__all__ = ["portfolio_router"]


def _operation_id(route: APIRoute) -> str:
    """Names an operation after its handler in camelCase, as the ledger's operations are named.

    FastAPI's default appends the path and the method, which is unique but reads badly in a client
    generated from the contract.
    """
    return to_camel(route.name)


def portfolio_router(view: ViewPosition, value: ValueHolding, dispose: DisposeUnits) -> APIRouter:
    """Build the routes over one holding.

    :param view: reads a position.
    :param value: values a holding at the latest price.
    :param dispose: prices selling units, oldest lot first.
    :returns: a router whose paths begin ``/holdings/{symbol}``. The caller chooses the prefix.
    """
    router = APIRouter(generate_unique_id_function=_operation_id)

    @router.get("/holdings/{symbol}/position")
    def read_position(symbol: str) -> PositionModel:
        """What is held in one instrument, and what it cost in total."""
        return PositionModel.of(view.of(Symbol(symbol)))

    @router.get("/holdings/{symbol}/valuation")
    def value_holding(symbol: str) -> ValuationModel:
        """What a holding is worth at the latest price, and the gain that is not yet realised."""
        return ValuationModel.of(value.of(Symbol(symbol)))

    @router.get("/holdings/{symbol}/disposal")
    def price_disposal(
        symbol: str,
        quantity: Annotated[str, Query(pattern=DECIMAL_PATTERN)],
        proceeds: Annotated[str, Query(pattern=DECIMAL_PATTERN)],
        currency: CurrencyCode,
    ) -> DispositionModel:
        """What selling a quantity of units for the given proceeds would realise, oldest lot first.

        A calculation that records nothing: no lot is consumed, so it is safe to repeat.
        """
        held = Symbol(symbol)
        units = Decimal(quantity)
        disposition = dispose.of(held, units, Money.of(proceeds, currency_for(currency)))
        return DispositionModel.of(held, units, disposition)

    return router
