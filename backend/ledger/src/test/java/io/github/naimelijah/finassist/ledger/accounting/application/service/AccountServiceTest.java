package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import org.junit.jupiter.api.Test;

/**
 * The contract of opening an account.
 *
 * <p>The rule under test is the one the domain could not hold: a name is unique across the chart of
 * accounts, which is a property of the set rather than of any value, so no constructor could check it.
 * Requirements listed it as TODO with exactly that explanation until this service existed.
 *
 * <p>Driven through the in-port with the in-memory adapters standing in for a store. They are the
 * fakes; writing a second set by hand would test a different pair of classes than the ones that run.
 */
class AccountServiceTest {

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService service = new AccountService(accounts, events);

    @Test
    void Given_a_free_name_When_an_account_is_opened_Then_it_is_stored_with_a_generated_id() {
        Account opened = service.open(new OpenAccountCommand("Current account", AccountType.ASSET, Currency.ILS));

        assertThat(opened.name()).isEqualTo("Current account");
        assertThat(opened.currency()).isEqualTo(Currency.ILS);
        assertThat(accounts.findById(opened.id())).contains(opened);
    }

    @Test
    void Given_a_name_already_open_When_opened_again_Then_it_is_refused() {
        service.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThatThrownBy(() -> service.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class)
                .hasMessageContaining("already open");
    }

    /**
     * The reason {@code OpenAccountCommand} normalises the name as well as {@code Account}. With the
     * strip left to the aggregate, the uniqueness lookup would search for " Groceries", find nothing,
     * and insert a second account whose stored name is identical to the first.
     */
    @Test
    void Given_a_name_differing_only_in_whitespace_When_opened_Then_it_is_still_a_duplicate() {
        service.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThatThrownBy(
                        () -> service.open(new OpenAccountCommand("  Groceries  ", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class);
    }

    @Test
    void Given_two_different_names_When_both_are_opened_Then_both_are_stored() {
        Account first = service.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.ILS));
        Account second = service.open(new OpenAccountCommand("Salary", AccountType.INCOME, Currency.ILS));

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(accounts.findByName("Salary")).contains(second);
    }

    @Test
    void Given_an_account_is_opened_When_it_succeeds_Then_the_event_carries_what_was_stored() {
        Account opened = service.open(new OpenAccountCommand(" Rent ", AccountType.EXPENSE, Currency.USD));

        assertThat(events.published())
                .containsExactly(
                        new AccountingEvent.AccountOpened(opened.id(), "Rent", AccountType.EXPENSE, Currency.USD));
    }

    /** A refused command must leave no trace, or the event log stops describing the books. */
    @Test
    void Given_a_duplicate_name_When_refused_Then_nothing_is_stored_and_nothing_is_published() {
        service.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.ILS));

        assertThatThrownBy(() -> service.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.USD)))
                .isInstanceOf(DuplicateAccountNameException.class);

        assertThat(events.published()).hasSize(1);
        assertThat(accounts.findByName("Cash").orElseThrow().currency()).isEqualTo(Currency.ILS);
    }

    @Test
    void Given_a_blank_name_When_the_command_is_built_Then_it_is_refused_at_the_boundary() {
        assertThatThrownBy(() -> new OpenAccountCommand("   ", AccountType.ASSET, Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
