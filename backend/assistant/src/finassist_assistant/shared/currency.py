"""The currencies FinAssist understands, with the decimal places each admits."""

from enum import Enum


class Currency(Enum):
    """ISO 4217 codes and their minor units.

    Mirrors ``io.github.naimelijah.finassist.ledger.shared.Currency`` deliberately. ADR-010
    forbids a shared kernel across languages, so this table is duplicated on purpose and kept
    honest by equivalent tests on both sides rather than by a shared artefact.

    The value is a ``(code, minor_units)`` pair rather than the minor units alone, because
    ``Enum`` treats members that share a value as *aliases*: with ``ILS = 2`` and ``USD = 2``,
    ``Currency.USD is Currency.ILS`` is true, every currency with two decimal places collapses
    into one member, and no mismatch can ever be detected. Java enums cannot express that
    mistake, which is exactly the kind of asymmetry the mirrored tests exist to catch - and did.

    ``code`` is the three-letter ISO 4217 code, which is what the wire carries; ``minor_units`` is
    the number of decimal places an amount in the currency admits (``JPY`` has none).
    """

    ILS = ("ILS", 2)
    USD = ("USD", 2)
    EUR = ("EUR", 2)
    GBP = ("GBP", 2)
    JPY = ("JPY", 0)

    code: str
    minor_units: int

    def __init__(self, code: str, minor_units: int) -> None:
        self.code = code
        self.minor_units = minor_units
