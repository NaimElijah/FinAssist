"""Frozen values the assistance context operates on: a question, evidence, and an answer."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from finassist_assistant.shared import Money


@dataclass(frozen=True, slots=True)
class EvidenceRef:
    """A pointer at the ledger record a figure came from.

    Deliberately a pair of strings rather than a typed accounting identifier: the accounting
    context owns those, and importing one here would couple two contexts. The anti-corruption
    layer turns a ledger response into these refs.
    """

    kind: str
    identifier: str

    def __post_init__(self) -> None:
        if not self.kind.strip():
            raise ValueError("Evidence must say what kind of record it points at")
        if not self.identifier.strip():
            raise ValueError("Evidence must point at an identifier")

    def __str__(self) -> str:
        return f"{self.kind}:{self.identifier}"


@dataclass(frozen=True, slots=True)
class Evidence:
    """One figure drawn from the ledger, and where it came from."""

    ref: EvidenceRef
    value: Money | Decimal
    as_of: date


@dataclass(frozen=True, slots=True)
class Claim:
    """One statement in an answer, with the evidence it rests on.

    A claim with no citations is fine when it states no figure. A claim that states a figure and
    cites nothing is the thing this context exists to catch.
    """

    text: str
    citations: tuple[EvidenceRef, ...] = ()

    def __post_init__(self) -> None:
        if not self.text.strip():
            raise ValueError("A claim must say something")


@dataclass(frozen=True, slots=True)
class GroundedAnswer:
    """An answer in which every figure is traceable to a ledger record."""

    claims: tuple[Claim, ...]

    @property
    def text(self) -> str:
        return " ".join(claim.text for claim in self.claims)


@dataclass(frozen=True, slots=True)
class Ungrounded:
    """A rejected answer, naming the claims that stated a figure without citing one.

    Returned rather than raised: an ungrounded answer is an expected outcome of asking a
    language model for numbers, not an exceptional one, and the caller has to decide what to
    show. Making it a value keeps that decision explicit.
    """

    offending: tuple[Claim, ...]

    @property
    def reason(self) -> str:
        return "Uncited figures in: " + "; ".join(claim.text for claim in self.offending)
