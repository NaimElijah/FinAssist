package io.github.naimelijah.finassist.ledger.accounting.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import org.junit.jupiter.api.Test;

/** An account has a type that gives its balance a meaning, and carries no balance of its own. */
class AccountTest {

    @Test
    void Given_an_asset_or_expense_When_asked_Then_it_increases_with_a_debit() {
        assertThat(AccountType.ASSET.increasesWithDebit()).isTrue();
        assertThat(AccountType.EXPENSE.increasesWithDebit()).isTrue();
    }

    @Test
    void Given_a_liability_income_or_equity_When_asked_Then_it_increases_with_a_credit() {
        assertThat(AccountType.LIABILITY.increasesWithDebit()).isFalse();
        assertThat(AccountType.INCOME.increasesWithDebit()).isFalse();
        assertThat(AccountType.EQUITY.increasesWithDebit()).isFalse();
    }

    @Test
    void Given_a_padded_name_When_an_account_is_opened_Then_the_name_is_stripped() {
        assertThat(Account.open("  Current account  ", AccountType.ASSET, Currency.ILS)
                        .name())
                .isEqualTo("Current account");
    }

    @Test
    void Given_a_blank_name_When_an_account_is_opened_Then_it_is_refused() {
        assertThatThrownBy(() -> Account.open("   ", AccountType.ASSET, Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void Given_two_opened_accounts_When_compared_Then_their_identities_differ() {
        Account first = Account.open("Current", AccountType.ASSET, Currency.ILS);
        Account second = Account.open("Current", AccountType.ASSET, Currency.ILS);
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void Given_an_account_id_as_text_When_parsed_Then_it_round_trips() {
        AccountId generated = AccountId.generate();
        assertThat(AccountId.of(generated.toString())).isEqualTo(generated);
    }

    @Test
    void Given_an_account_type_When_asked_its_normal_balance_Then_the_side_is_named() {
        assertThat(AccountType.ASSET.normalBalance()).isEqualTo(AccountType.NormalBalance.DEBIT);
        assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(AccountType.NormalBalance.DEBIT);
        assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
        assertThat(AccountType.INCOME.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
        assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(AccountType.NormalBalance.CREDIT);
    }
}
