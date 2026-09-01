"""LedgerPort, QuotePort as typing.Protocol. Adapters implement these; never the reverse.

Protocols rather than base classes, so an adapter satisfies a port by shape and never has to
import it. Stdlib only: an architecture rule forbids every inward module from importing a
declared runtime dependency, so there is no pydantic here and there never will be. Transport
models belong in ``adapters/inbound/api`` and are mapped at the edge.

Synchronous, per ADR-021. Nothing here fans out concurrent I/O, and the trigger to revisit is
the first use case that must.
"""

from typing import Protocol

from finassist_assistant.portfolio.domain.model import Lot, Symbol
from finassist_assistant.shared import Money

__all__ = [
    "HoldingNotFoundError",
    "LedgerPort",
    "PriceUnavailableError",
    "QuotePort",
]


class HoldingNotFoundError(LookupError):
    """Nothing is held for the symbol, so there is no position to speak of.

    Distinct from an empty position, which cannot exist: ``analysis.position`` refuses an empty
    tuple, because a position of zero units at a cost of zero would be indistinguishable from a
    holding that was sold and would quietly answer questions about instruments never owned.
    """

    def __init__(self, symbol: Symbol) -> None:
        super().__init__(f"No lots are held for {symbol}")
        self.symbol = symbol


class PriceUnavailableError(LookupError):
    """No price can be had for the symbol, whether because none exists or the last is too old.

    The distinction belongs to whoever implements ``QuotePort`` and is deliberately erased here:
    portfolio cannot name market data's staleness rule without importing another context, and a
    valuation is equally impossible either way.
    """

    def __init__(self, symbol: Symbol) -> None:
        super().__init__(f"No usable price is available for {symbol}")
        self.symbol = symbol


class LedgerPort(Protocol):
    """The holdings the ledger knows about, already translated into portfolio's own model.

    The anti-corruption layer sits behind this, in ``adapters/outbound/ledger``: ledger DTOs
    become ``Lot`` values there and nowhere else (ADR-007).
    """

    def lots_for(self, symbol: Symbol) -> tuple[Lot, ...]:
        """Every lot held for the symbol. An empty tuple when none is."""
        ...


class QuotePort(Protocol):
    """A price for one unit, fresh enough to value a holding with.

    Portfolio asks for a price, not a quote: a ``Quote`` belongs to ``marketdata`` and naming it
    here would be a cross-context import. Whoever satisfies this port decides what "fresh
    enough" means and raises :class:`PriceUnavailableError` when it cannot be met.
    """

    def unit_price(self, symbol: Symbol) -> Money:
        """The price of one unit.

        :raises PriceUnavailableError: if no usable price exists
        """
        ...
