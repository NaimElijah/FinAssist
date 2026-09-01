"""Orchestration only: gather evidence, ask, check grounding, answer."""

from dataclasses import dataclass

from finassist_assistant.assistance.application.port import EvidencePort, LlmPort
from finassist_assistant.assistance.domain.grounding import ground
from finassist_assistant.assistance.domain.model import GroundedAnswer, Ungrounded

__all__ = ["AnswerQuestion"]


@dataclass(frozen=True, slots=True)
class AnswerQuestion:
    """Answers a question about the owner's own data, and refuses an ungrounded answer.

    Returns a union rather than raising, because ``Ungrounded`` is an answer about the answer:
    it names the offending claims, which is what a caller needs in order to say *why* nothing
    was returned. ADR-014 makes the rule; this is the only place that applies it.

    The check is deliberately outside the model's control. Asking a model to cite its sources
    and trusting that it did is not a rule, it is a hope — so what comes back is examined here,
    and an uncited figure loses the whole answer.
    """

    evidence: EvidencePort
    llm: LlmPort

    def about(self, question: str) -> GroundedAnswer | Ungrounded:
        """
        :raises ValueError: if the question says nothing, or the model returns no claims at all
        """
        asked = question.strip()
        if not asked:
            raise ValueError("A question must ask something")

        gathered = self.evidence.gather(asked)
        # An empty tuple is passed through rather than short-circuited: whether a question can be
        # answered without evidence is the model's to decide, and the grounding rule catches it
        # either way the moment the answer states a figure.
        return ground(self.llm.answer(asked, gathered))
