"""Orchestration only: gather evidence, ask, check grounding, answer."""

from dataclasses import dataclass, replace

from finassist_assistant.assistance.application.port import Evidence, EvidencePort, LlmPort
from finassist_assistant.assistance.domain.grounding import ground
from finassist_assistant.assistance.domain.model import (
    Claim,
    EvidenceRef,
    GroundedAnswer,
    Ungrounded,
)

__all__ = ["AnswerQuestion"]


def _cited_from(evidence: tuple[Evidence, ...]) -> frozenset[EvidenceRef]:
    return frozenset(item.ref for item in evidence)


def _keep_real_citations(claim: Claim, gathered: frozenset[EvidenceRef]) -> Claim:
    """Drops citations that point at no evidence this answer was actually given.

    ADR-014 requires a claim to cite *the ledger record it came from*. A reference the model
    invented points at no record it was shown, so it is not that - and ``is_grounded`` only checks
    that the citation tuple is non-empty, which any citation-shaped string satisfies.

    Dropping rather than raising is what keeps the rule in one place: a claim left with no
    citations is judged by ``ground`` exactly as an uncited one, so a fabricated reference on a
    claim that states a figure loses the answer, and one on a claim that states no figure is
    harmlessly discarded.
    """
    kept = tuple(ref for ref in claim.citations if ref in gathered)
    return claim if len(kept) == len(claim.citations) else replace(claim, citations=kept)


@dataclass(frozen=True, slots=True)
class AnswerQuestion:
    """Answers a question about the owner's own data, and refuses an ungrounded answer.

    Returns a union rather than raising, because ``Ungrounded`` is an answer about the answer:
    it names the offending claims, which is what a caller needs in order to say *why* nothing
    was returned. ADR-014 makes the rule; this is the only place that applies it.

    The check is deliberately outside the model's control, in both halves. Asking a model to cite
    its sources and trusting that it did is not a rule, it is a hope — so what comes back is
    examined here, and an uncited figure loses the whole answer. **And a citation counts only when
    it points at evidence this answer was actually given**, which is a check nothing else can make:
    the domain never sees the evidence, and this use case is the only place holding both.
    """

    evidence: EvidencePort
    llm: LlmPort

    def about(self, question: str) -> GroundedAnswer | Ungrounded:
        """Answers the question from the evidence gathered for it, or says why it cannot.

        :returns: a ``GroundedAnswer`` when every claim that states a figure cites evidence this
            answer was given, otherwise an ``Ungrounded`` naming the claims that do not.
        :raises ValueError: if the question says nothing, or the model returns no claims at all
        """
        asked = question.strip()
        if not asked:
            raise ValueError("A question must ask something")

        gathered = self.evidence.gather(asked)
        # An empty tuple is passed through rather than short-circuited: whether a question can be
        # answered without evidence is the model's to decide, and the grounding rule catches it
        # either way the moment the answer states a figure.
        claims = self.llm.answer(asked, gathered)

        known = _cited_from(gathered)
        return ground(tuple(_keep_real_citations(claim, known) for claim in claims))
