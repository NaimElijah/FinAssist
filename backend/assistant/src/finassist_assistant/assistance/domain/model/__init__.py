"""Frozen values the assistance context operates on: claims, citations, and an answer.

There is deliberately no ``Evidence`` type carrying a value. Nothing reads one - a citation is an
:class:`EvidenceRef`, and the figure itself lives in the claim text - so it was removed rather
than left as scaffolding. It returns when something needs to compare a cited value against the
figure a claim states, which is the tightening ADR-014 names.
"""

from __future__ import annotations

from dataclasses import dataclass


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
        kind = self.kind.strip()
        identifier = self.identifier.strip()
        if not kind:
            raise ValueError("Evidence must say what kind of record it points at")
        if not identifier:
            raise ValueError("Evidence must point at an identifier")
        # Normalised, not merely validated: every other value type in the domain strips and
        # assigns back, and without it two refs differing only in padding are different values.
        object.__setattr__(self, "kind", kind)
        object.__setattr__(self, "identifier", identifier)

    def __str__(self) -> str:
        return f"{self.kind}:{self.identifier}"


@dataclass(frozen=True, slots=True)
class Claim:
    """One statement in an answer, with the evidence it rests on.

    A claim with no citations is fine when it states no figure. A claim that states a figure and
    cites nothing is the thing this context exists to catch.
    """

    text: str
    citations: tuple[EvidenceRef, ...] = ()

    def __post_init__(self) -> None:
        text = self.text.strip()
        if not text:
            raise ValueError("A claim must say something")
        object.__setattr__(self, "text", text)

        # Validated at the boundary rather than trusted from the type hint. The callers will be
        # LLM adapters that do not exist yet, and without this a bare string counted as a
        # citation - grounding a figure with nothing - while a list silently made the frozen
        # dataclass unhashable and broke GroundedAnswer.
        if not isinstance(self.citations, tuple):
            raise TypeError(
                f"citations must be a tuple of EvidenceRef, not {type(self.citations).__name__}"
            )
        for citation in self.citations:
            if not isinstance(citation, EvidenceRef):
                raise TypeError(
                    f"Every citation must be an EvidenceRef, not {type(citation).__name__}"
                )


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
