"""Answering a question, and refusing an answer that states a figure it cannot back.

ADR-014 makes the rule; this use case is the only place that applies it. The check is outside the
model's control on purpose — asking a model to cite its sources and trusting that it did is not a
rule, it is a hope.
"""

import pytest

from finassist_assistant.assistance.application.port import Evidence
from finassist_assistant.assistance.application.usecase import AnswerQuestion
from finassist_assistant.assistance.domain.model import (
    Claim,
    EvidenceRef,
    GroundedAnswer,
    Ungrounded,
)

BALANCE = EvidenceRef("balance", "b7f2")
EVIDENCE = Evidence(BALANCE, "Current account, 1240.50 ILS on 2026-03-01")


class FakeEvidence:
    def __init__(self, *evidence: Evidence) -> None:
        self.evidence = evidence
        self.asked: list[str] = []

    def gather(self, question: str) -> tuple[Evidence, ...]:
        self.asked.append(question)
        return self.evidence


class FakeLlm:
    """Says whatever it was told to say. No API key, no network, no cost."""

    def __init__(self, *claims: Claim) -> None:
        self.claims = claims
        self.saw: tuple[Evidence, ...] = ()
        self.asked: list[str] = []

    def answer(self, question: str, evidence: tuple[Evidence, ...]) -> tuple[Claim, ...]:
        self.asked.append(question)
        self.saw = evidence
        return self.claims


def Given_every_figure_cited_When_a_question_is_asked_Then_the_answer_is_returned() -> None:
    llm = FakeLlm(Claim("You spent 1240.50 on groceries.", (BALANCE,)))

    answer = AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("What did I spend on groceries?")

    assert isinstance(answer, GroundedAnswer)
    assert answer.text == "You spent 1240.50 on groceries."


def Given_an_uncited_figure_When_a_question_is_asked_Then_the_answer_is_rejected() -> None:
    invented = Claim("You spent 9999.00 on holidays.")
    llm = FakeLlm(Claim("Spending is up.", (BALANCE,)), invented)

    answer = AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("How am I doing?")

    assert isinstance(answer, Ungrounded)
    assert answer.offending == (invented,)


def Given_a_question_When_asked_Then_the_gathered_evidence_reaches_the_model() -> None:
    llm = FakeLlm(Claim("Spending is steady."))
    AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("How am I doing?")

    assert llm.saw == (EVIDENCE,)


def Given_a_padded_question_When_asked_Then_both_ports_see_the_stripped_form() -> None:
    evidence = FakeEvidence(EVIDENCE)
    llm = FakeLlm(Claim("Spending is steady."))

    AnswerQuestion(evidence, llm).about("  How am I doing?  ")

    assert evidence.asked == ["How am I doing?"]
    assert llm.asked == ["How am I doing?"]


def Given_a_blank_question_When_asked_Then_it_is_refused_before_anything_is_gathered() -> None:
    evidence = FakeEvidence(EVIDENCE)

    with pytest.raises(ValueError, match="must ask something"):
        AnswerQuestion(evidence, FakeLlm()).about("   ")

    assert evidence.asked == []


def Given_no_evidence_at_all_When_asked_Then_the_model_is_still_consulted() -> None:
    # Whether a question can be answered without evidence is the model's to decide. The grounding
    # rule catches it either way the moment the answer states a figure.
    llm = FakeLlm(Claim("I have nothing on file for that."))

    answer = AnswerQuestion(FakeEvidence(), llm).about("What did I spend in 1999?")

    assert isinstance(answer, GroundedAnswer)
    assert llm.saw == ()


def Given_a_model_returning_nothing_When_asked_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="at least one claim"):
        AnswerQuestion(FakeEvidence(EVIDENCE), FakeLlm()).about("How am I doing?")


def Given_evidence_with_no_summary_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must say something about"):
        Evidence(BALANCE, "   ")


def Given_a_citation_pointing_at_evidence_never_gathered_When_asked_Then_it_does_not_ground() -> (
    None
):
    # is_grounded only checks that the citation tuple is non-empty, so any citation-shaped string
    # satisfies it. This use case is the only place holding both the evidence and the claims, so it
    # is the only place that can tell the difference between a real reference and an invented one.
    invented = Claim("You spent 9999.00 on holidays.", (EvidenceRef("balance", "made-up"),))
    llm = FakeLlm(invented)

    answer = AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("How am I doing?")

    assert isinstance(answer, Ungrounded)
    assert answer.offending[0].text == invented.text


def Given_a_citation_pointing_at_gathered_evidence_When_asked_Then_it_grounds() -> None:
    llm = FakeLlm(Claim("You spent 1240.50 on groceries.", (BALANCE,)))

    assert isinstance(
        AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("What did I spend?"), GroundedAnswer
    )


def Given_an_invented_citation_on_a_claim_with_no_figure_When_asked_Then_it_is_harmless() -> None:
    # Dropped rather than raised on, so the grounding rule stays the only judge: a claim stating no
    # figure needs no citation, and losing a bogus one costs nothing.
    llm = FakeLlm(Claim("Your spending looks steady.", (EvidenceRef("balance", "made-up"),)))

    answer = AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("How am I doing?")

    assert isinstance(answer, GroundedAnswer)
    assert answer.claims[0].citations == ()


def Given_one_real_and_one_invented_citation_When_asked_Then_the_real_one_still_grounds() -> None:
    llm = FakeLlm(Claim("You spent 1240.50.", (BALANCE, EvidenceRef("balance", "made-up"))))

    answer = AnswerQuestion(FakeEvidence(EVIDENCE), llm).about("What did I spend?")

    assert isinstance(answer, GroundedAnswer)
    assert answer.claims[0].citations == (BALANCE,)
