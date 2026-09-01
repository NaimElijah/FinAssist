"""LlmPort, EvidencePort as typing.Protocol.

LlmPort exists so the use cases can be tested against a fake, with no API key and no network.
"""

from dataclasses import dataclass
from typing import Protocol

from finassist_assistant.assistance.domain.model import Claim, EvidenceRef

__all__ = ["Evidence", "EvidencePort", "LlmPort"]


@dataclass(frozen=True, slots=True)
class Evidence:
    """A record the answer may cite, and enough of it to answer from.

    ``EvidenceRef`` is the citation — a kind and an identifier, which is all a reader needs to
    check a figure. This adds the content, because a model cannot answer from a pointer. Keeping
    them separate is what stops the whole record travelling into a citation.
    """

    ref: EvidenceRef
    summary: str

    def __post_init__(self) -> None:
        summary = self.summary.strip()
        if not summary:
            raise ValueError("Evidence must say something about the record it points at")
        object.__setattr__(self, "summary", summary)


class EvidencePort(Protocol):
    """Finds the records that bear on a question."""

    def gather(self, question: str) -> tuple[Evidence, ...]:
        """Evidence relevant to the question, empty when there is none."""
        ...


class LlmPort(Protocol):
    """The model that turns evidence into claims.

    A port, so the use case can be exercised with a fake that returns fixed claims — no API key,
    no network, no cost, and a grounding rule that can be tested against an answer designed to
    break it.

    Nothing implements this. An Anthropic client would decide by implication whether the owner's
    financial data may be sent to a third-party API, which the requirements reserve for its own
    decision and which no adapter should settle quietly. See ADR-021.
    """

    def answer(self, question: str, evidence: tuple[Evidence, ...]) -> tuple[Claim, ...]:
        """The claims the model makes, each carrying whatever it chose to cite."""
        ...
