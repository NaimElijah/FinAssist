"""Whether an answer is supported by the ledger, decided by code rather than by a prompt.

The rule: a claim whose text states a figure must cite at least one piece of evidence. A model
that invents a number produces an uncited figure, and an uncited figure is rejected here — not
noticed later by whoever happens to read the output.

This is what holds the system to its own non-goal of not being financial advice: FinAssist
reports on the owner's data, so every number it reports must come from that data (ADR-014).
"""

from __future__ import annotations

import re

from finassist_assistant.assistance.domain.model import Claim, GroundedAnswer, Ungrounded

# Any digit counts as a figure. Deliberately blunt: over-reporting a claim as needing a citation
# costs a citation, while under-reporting lets an invented number through, and those two are not
# equally bad.
_FIGURE = re.compile(r"\d")


def states_a_figure(text: str) -> bool:
    """True when the text contains a number, and therefore something that must be sourced."""
    return _FIGURE.search(text) is not None


def is_grounded(claim: Claim) -> bool:
    """A claim is grounded when it states no figure, or cites evidence for the ones it states."""
    return not states_a_figure(claim.text) or len(claim.citations) > 0


def ground(claims: tuple[Claim, ...]) -> GroundedAnswer | Ungrounded:
    """Accepts an answer only if every claim in it is grounded.

    :raises ValueError: if there are no claims; an empty answer is not a grounded one
    """
    if not claims:
        raise ValueError("An answer needs at least one claim")

    offending = tuple(claim for claim in claims if not is_grounded(claim))
    if offending:
        return Ungrounded(offending)
    return GroundedAnswer(claims)


__all__ = ["ground", "is_grounded", "states_a_figure"]
