"""An answer is accepted only when every figure in it cites the ledger (ADR-014)."""

import pytest

from finassist_assistant.assistance.domain.grounding import (
    ground,
    is_grounded,
    states_a_figure,
)
from finassist_assistant.assistance.domain.model import (
    Claim,
    EvidenceRef,
    GroundedAnswer,
    Ungrounded,
)

BALANCE = EvidenceRef("balance", "b7f2")


def Given_text_containing_a_number_When_examined_Then_it_states_a_figure() -> None:
    assert states_a_figure("You spent 1,240.50 on groceries")
    assert states_a_figure("Down 3%")


def Given_text_with_no_number_When_examined_Then_it_states_no_figure() -> None:
    assert not states_a_figure("Your grocery spending is up on last month")


def Given_a_claim_with_a_figure_and_a_citation_When_checked_Then_it_is_grounded() -> None:
    assert is_grounded(Claim("You spent 1240.50 on groceries", (BALANCE,)))


def Given_a_claim_with_a_figure_and_no_citation_When_checked_Then_it_is_not_grounded() -> None:
    assert not is_grounded(Claim("You spent 1240.50 on groceries"))


def Given_a_claim_with_no_figure_and_no_citation_When_checked_Then_it_is_grounded() -> None:
    assert is_grounded(Claim("Your grocery spending is up on last month"))


def Given_every_claim_grounded_When_grounded_Then_the_answer_is_accepted() -> None:
    claims = (
        Claim("Your spending is up."),
        Claim("You spent 1240.50 on groceries.", (BALANCE,)),
    )
    answer = ground(claims)
    assert isinstance(answer, GroundedAnswer)
    assert answer.text == "Your spending is up. You spent 1240.50 on groceries."


def Given_one_uncited_figure_When_grounded_Then_the_answer_is_rejected() -> None:
    invented = Claim("You spent 9999.00 on holidays.")
    answer = ground((Claim("Your spending is up.", (BALANCE,)), invented))
    assert isinstance(answer, Ungrounded)
    assert answer.offending == (invented,)
    assert "9999.00" in answer.reason


def Given_no_claims_When_grounded_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="at least one claim"):
        ground(())


def Given_a_blank_claim_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="must say something"):
        Claim("   ")


def Given_an_evidence_ref_without_an_identifier_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="identifier"):
        EvidenceRef("balance", "  ")


def Given_a_padded_evidence_ref_When_built_Then_it_is_normalised() -> None:
    assert EvidenceRef("  balance  ", "  b7f2 ") == BALANCE
    assert str(BALANCE) == "balance:b7f2"


def Given_a_figure_written_in_words_When_uncited_Then_the_answer_is_rejected() -> None:
    invented = Claim("You spent one thousand two hundred shekels on holidays.")
    answer = ground((invented,))
    assert isinstance(answer, Ungrounded)
    assert answer.offending == (invented,)


def Given_a_citation_that_is_not_an_evidence_ref_When_a_claim_is_built_Then_it_is_refused() -> None:
    with pytest.raises(TypeError, match="EvidenceRef"):
        Claim("You spent 12.50", ("not-an-evidence-ref",))  # type: ignore[arg-type]


def Given_citations_as_a_list_When_a_claim_is_built_Then_it_is_refused() -> None:
    with pytest.raises(TypeError, match="tuple"):
        Claim("You spent 12.50", [BALANCE])  # type: ignore[arg-type]


def Given_evidence_without_a_kind_When_built_Then_it_is_refused() -> None:
    with pytest.raises(ValueError, match="what kind of record"):
        EvidenceRef("   ", "txn-1")


def Given_several_uncited_figures_When_grounded_Then_every_offender_is_named() -> None:
    first = Claim("Your balance is 1200.00 ILS")
    second = Claim("You spent 300.00 ILS on groceries")
    grounded = Claim("Spending looks steady", (EvidenceRef("transaction", "txn-1"),))

    answer = ground((first, grounded, second))

    assert isinstance(answer, Ungrounded)
    assert answer.offending == (first, second)
    assert "; " in answer.reason


def Given_a_capitalised_number_word_When_examined_Then_it_still_states_a_figure() -> None:
    assert states_a_figure("One thousand shekels") is True


def Given_a_claim_with_no_citations_argument_When_built_Then_it_has_none() -> None:
    assert Claim("Spending looks steady").citations == ()
