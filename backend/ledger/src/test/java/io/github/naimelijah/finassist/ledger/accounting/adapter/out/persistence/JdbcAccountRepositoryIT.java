package io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What {@code AccountRepository}'s Javadoc promises, asserted against the store that has to keep it.
 *
 * <p>The uniqueness rule is the reason this file exists. {@code AccountService} checks for a taken
 * name before writing, and says in its own comment that the check is for the message rather than for
 * the rule — a check followed by a write is not atomic, and two callers can pass it together. What
 * actually holds the rule is a unique index, and an index is not something a unit test can be shown.
 */
@SpringBootTest
class JdbcAccountRepositoryIT extends PostgresFixture {

    @Autowired
    private AccountRepository accounts;

    @Test
    void Given_an_account_When_it_is_saved_Then_it_is_read_back_whole() {
        Account cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);

        accounts.save(cash);

        assertThat(accounts.findById(cash.id())).contains(cash);
    }

    @Test
    void Given_no_such_account_When_it_is_looked_up_Then_nothing_is_found() {
        assertThat(accounts.findById(AccountId.generate())).isEmpty();
        assertThat(accounts.findByName("nothing is called this")).isEmpty();
    }

    /** The rule the service could not hold, held by the index rather than by a preceding query. */
    @Test
    void Given_a_name_already_taken_When_another_account_claims_it_Then_the_index_refuses_it() {
        accounts.save(Account.open("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThatThrownBy(() -> accounts.save(Account.open("Groceries", AccountType.EXPENSE, Currency.USD)))
                .isInstanceOf(DuplicateAccountNameException.class);
    }

    /**
     * The chart of accounts holding both "Groceries" and "groceries" is a mistake, not a distinction:
     * every balance and journal query would split between two accounts a person reads as one.
     */
    @Test
    void Given_a_name_differing_only_in_case_When_it_is_claimed_Then_it_is_still_a_duplicate() {
        accounts.save(Account.open("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThatThrownBy(() -> accounts.save(Account.open("GROCERIES", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class);
    }

    @Test
    void Given_a_name_differing_only_in_whitespace_When_it_is_claimed_Then_it_is_still_a_duplicate() {
        accounts.save(Account.open("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThatThrownBy(() -> accounts.save(Account.open("  Groceries  ", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class);
    }

    @Test
    void Given_an_account_When_it_is_looked_up_by_a_differently_cased_name_Then_it_is_found() {
        Account groceries = Account.open("Groceries", AccountType.EXPENSE, Currency.ILS);
        accounts.save(groceries);

        assertThat(accounts.findByName("GROCERIES")).contains(groceries);
        assertThat(accounts.findByName("  groceries ")).contains(groceries);
    }

    /** The display form is what a person reads on a report, so it is stored as it was typed. */
    @Test
    void Given_a_mixed_case_name_When_it_is_read_back_Then_the_typed_form_is_what_was_stored() {
        Account groceries = Account.open("Groceries", AccountType.EXPENSE, Currency.ILS);
        accounts.save(groceries);

        assertThat(accounts.findByName("groceries").orElseThrow().name()).isEqualTo("Groceries");
    }

    /**
     * Re-saving must not trip the name claim the account already holds. The port has no delete, so
     * {@code save} is the only writer and has to be idempotent for an identity it already stored.
     */
    @Test
    void Given_an_account_already_stored_When_it_is_saved_again_Then_it_is_accepted() {
        Account cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        accounts.save(cash);

        accounts.save(cash);

        assertThat(accounts.findById(cash.id())).contains(cash);
    }

    /**
     * A JPY amount has no minor units, and {@code Money} refuses an amount carrying more precision
     * than its currency admits rather than rounding it away. An account is where the currency is
     * stored, so this is where a mismatch would first surface.
     */
    @Test
    void Given_an_account_in_a_currency_with_no_minor_units_When_read_back_Then_its_currency_survives() {
        Account tokyo = Account.open("Tokyo cash", AccountType.ASSET, Currency.JPY);
        accounts.save(tokyo);

        assertThat(accounts.findById(tokyo.id()).orElseThrow().currency()).isEqualTo(Currency.JPY);
    }

    /**
     * The order a listing promises: name, ignoring case. {@code normalised_name} is what the unique
     * index and this {@code ORDER BY} both key on, so the two can never disagree.
     */
    @Test
    void Given_several_accounts_When_all_are_listed_Then_they_come_back_ordered_by_name_ignoring_case() {
        accounts.save(Account.open("savings", AccountType.ASSET, Currency.ILS));
        accounts.save(Account.open("Cash", AccountType.ASSET, Currency.ILS));
        accounts.save(Account.open("brokerage", AccountType.ASSET, Currency.ILS));

        assertThat(accounts.all()).extracting(Account::name).containsExactly("brokerage", "Cash", "savings");
    }

    @Test
    void Given_no_accounts_at_all_When_they_are_listed_Then_the_answer_is_empty_rather_than_null() {
        assertThat(accounts.all()).isEmpty();
    }

    /** The claim the whole exercise was for: it is in the database, not in this process. */
    @Test
    void Given_an_account_When_read_over_an_independent_connection_Then_it_is_still_there() {
        Account cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        accounts.save(cash);

        AccountRepository elsewhere = new JdbcAccountRepository(freshConnection());

        assertThat(elsewhere.findById(cash.id())).contains(cash);
    }
}
