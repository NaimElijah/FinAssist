package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import org.junit.jupiter.api.Test;

/** One side of a transaction: signed, never zero, and never asked which way an account moves. */
class EntryTest {

    private static final AccountId CHECKING = AccountId.generate();

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    @Test
    void Given_a_positive_amount_When_an_entry_is_built_Then_it_is_a_debit() {
        Entry entry = new Entry(CHECKING, ils("12.50"));

        assertThat(entry.isDebit()).isTrue();
        assertThat(entry.isCredit()).isFalse();
    }

    @Test
    void Given_a_negative_amount_When_an_entry_is_built_Then_it_is_a_credit() {
        Entry entry = new Entry(CHECKING, ils("-12.50"));

        assertThat(entry.isCredit()).isTrue();
        assertThat(entry.isDebit()).isFalse();
    }

    /**
     * Debit and credit are the sign of the amount and nothing else. An entry holds an {@link
     * AccountId}, not an {@link
     * io.github.naimelijah.finassist.ledger.accounting.domain.account.Account}, so it cannot consult
     * the account's normal balance even if it wanted to.
     */
    @Test
    void Given_an_entry_When_asked_its_side_Then_the_answer_comes_from_the_sign_alone() {
        assertThat(new Entry(CHECKING, ils("0.01")).isDebit()).isTrue();
        assertThat(new Entry(CHECKING, ils("-0.01")).isDebit()).isFalse();
    }

    @Test
    void Given_an_amount_of_zero_When_an_entry_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Entry(CHECKING, Money.zero(Currency.ILS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records nothing");
    }

    @Test
    void Given_no_account_When_an_entry_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Entry(null, ils("12.50"))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void Given_no_amount_When_an_entry_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Entry(CHECKING, null)).isInstanceOf(NullPointerException.class);
    }
}
