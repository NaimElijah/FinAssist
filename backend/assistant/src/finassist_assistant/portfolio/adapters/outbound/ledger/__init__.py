"""The anti-corruption layer over the ledger's published language.

Ledger DTOs become portfolio's own model here and nowhere else (ADR-007). The HTTP client that
will do the fetching does not exist: ADR-004 is still Proposed, and the ledger has no endpoint
to call. What is here instead is an in-memory stand-in, so the use cases can be assembled and
run while that decision is open.
"""

from collections.abc import Iterable

from finassist_assistant.portfolio.application.port import LedgerPort
from finassist_assistant.portfolio.domain.model import Lot, Symbol

__all__ = ["InMemoryLedger"]


class InMemoryLedger(LedgerPort):
    """Holds lots in memory. A development seam, not a client and not a store.

    It is replaced by the real anti-corruption layer once there is an endpoint and ADR-004 is
    ratified — not grown into one. Nothing here translates anything, because nothing foreign has
    arrived yet.
    """

    def __init__(self, lots: Iterable[Lot] = ()) -> None:
        self._lots: dict[Symbol, tuple[Lot, ...]] = {}
        for lot in lots:
            self.add(lot)

    def add(self, lot: Lot) -> None:
        """Appends a lot, keeping the order it arrived in.

        Order is not the FIFO guarantee: ``DisposeUnits`` sorts by acquisition date before
        consuming, precisely so that a feed delivering lots out of order cannot turn a FIFO cost
        basis into a LIFO one.
        """
        self._lots[lot.symbol] = (*self._lots.get(lot.symbol, ()), lot)

    def lots_for(self, symbol: Symbol) -> tuple[Lot, ...]:
        return self._lots.get(symbol, ())
