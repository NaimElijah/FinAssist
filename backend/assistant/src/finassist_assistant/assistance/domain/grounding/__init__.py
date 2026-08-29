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

# Deliberately blunt: over-reporting a claim as needing a citation costs a citation, while
# under-reporting lets an invented number through, and those two are not equally bad.
_DIGIT = re.compile(r"\d")
_WORD = re.compile(r"[a-z]+")

# Digits alone missed the case that matters most. A model asked for figures writes them in prose
# as readily as in digits, and "you spent one thousand two hundred shekels" was being accepted as
# grounded with no citation at all - precisely the failure this context exists to prevent.
_NUMBER_WORDS = frozenset(
    {
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "ten",
        "eleven",
        "twelve",
        "thirteen",
        "fourteen",
        "fifteen",
        "sixteen",
        "seventeen",
        "eighteen",
        "nineteen",
        "twenty",
        "thirty",
        "forty",
        "fifty",
        "sixty",
        "seventy",
        "eighty",
        "ninety",
        "hundred",
        "thousand",
        "million",
        "billion",
        "half",
        "quarter",
        "third",
        "double",
        "triple",
        "dozen",
    }
)


def states_a_figure(text: str) -> bool:
    """True when the text contains a number, in digits or in words.

    Still not exhaustive: "a couple of hundred" is caught by "hundred", but "a fair bit more" is
    not, and no word list closes that. The real fix is a claim carrying its figure as a typed
    value rather than embedded in prose, which ADR-014 names as the tightening to make.
    """
    if _DIGIT.search(text) is not None:
        return True
    return any(word in _NUMBER_WORDS for word in _WORD.findall(text.lower()))


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
