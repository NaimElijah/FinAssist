"""The anti-corruption layer over the ledger's published language.

Ledger DTOs become portfolio's own model here and nowhere else (ADR-007). The HTTP client that
will do the fetching does not exist yet. ADR-004 is Accepted and the ledger already serves
accounting, budgeting and planning under ``/api/v1`` — but none of them is a lot: nothing in the
ledger's published contract has a holding to fetch. What is here instead is an in-memory
stand-in, so the use cases can be assembled and run while that gap is open.
"""

from collections.abc import Iterable

from finassist_assistant.portfolio.domain.model import Lot, Symbol

__all__ = ["InMemoryLedger"]


class InMemoryLedger:
    """Holds lots in memory. A development seam, not a client and not a store.

    It satisfies ``LedgerPort`` by shape and does not inherit it, which is what the port itself
    asks for. Subclassing would be worse than redundant: a Protocol member declared with a ``...``
    body is not abstract, so a subclass that misspelled ``lots_for`` would instantiate happily,
    inherit the stub and return ``None`` — reported as "nothing is held" for a symbol that is.
    Structural typing has no stub to inherit, and mypy checks the fit where the use cases are
    constructed in ``adapters/wiring.py``.

    It is replaced by the real anti-corruption layer once the ledger publishes a holding and the
    assistant gains a client for it — not grown into one. Nothing here translates anything,
    because nothing foreign has arrived yet.
    """

    def __init__(self, lots: Iterable[Lot] = ()) -> None:
        self._lots: dict[Symbol, tuple[Lot, ...]] = {}
        for lot in lots:
            self.add(lot)

    def add(self, lot: Lot) -> None:
        """Appends a lot, keeping the order it arrived in.

        Order is not the FIFO guarantee: ``consume_fifo`` sorts by acquisition date itself
        (ADR-041), so a feed delivering lots out of order cannot turn a FIFO cost basis into a
        LIFO one. The order kept here still decides between lots acquired on the same day.
        """
        self._lots[lot.symbol] = (*self._lots.get(lot.symbol, ()), lot)

    def lots_for(self, symbol: Symbol) -> tuple[Lot, ...]:
        """Every lot held for the symbol, in the order added. An empty tuple when none is."""
        return self._lots.get(symbol, ())
