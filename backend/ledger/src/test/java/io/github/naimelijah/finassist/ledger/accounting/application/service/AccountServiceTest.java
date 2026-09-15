package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.DuplicateAccountNameException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The contract of opening an account.
 *
 * <p>The rule under test is the one the domain could not hold: a name is unique across the chart of
 * accounts, which is a property of the set rather than of any value, so no constructor could check
 * it. Requirements listed it as TODO with exactly that explanation until this service existed.
 *
 * <p>Driven through the in-port against a mocked repository. What the service owns is the *message*
 * — telling a caller which name is taken — and the decision not to publish an event for a command it
 * refused. What actually makes the rule hold is the unique index, because a check followed by a
 * write is not atomic, and that is asserted where it lives, in {@code JdbcAccountRepositoryIT}
 * against a real database (ADR-024, ADR-025).
 */
class AccountServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService service = new AccountService(accounts, events);

    @Test
    void Given_a_free_name_When_an_account_is_opened_Then_it_is_stored_with_a_generated_id() {
        when(accounts.findByName("Current account")).thenReturn(Optional.empty());

        Account opened = service.open(new OpenAccountCommand("Current account", AccountType.ASSET, Currency.ILS));

        assertThat(opened.name()).isEqualTo("Current account");
        assertThat(opened.currency()).isEqualTo(Currency.ILS);
        assertThat(opened.id()).isNotNull();
        verify(accounts).save(opened);
    }

    @Test
    void Given_a_name_already_open_When_opened_again_Then_it_is_refused() {
        when(accounts.findByName("Groceries"))
                .thenReturn(Optional.of(Account.open("Groceries", AccountType.EXPENSE, Currency.ILS)));

        assertThatThrownBy(() -> service.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class)
                .hasMessageContaining("already open");
    }

    /**
     * The reason {@code OpenAccountCommand} normalises the name as well as {@code Account}. With the
     * strip left to the aggregate, the service would ask the repository for " Groceries" rather than
     * "Groceries" — and a store matching on the exact string would find nothing and let a second
     * account through.
     */
    @Test
    void Given_a_name_differing_only_in_whitespace_When_opened_Then_the_lookup_uses_the_stripped_form() {
        when(accounts.findByName("Groceries"))
                .thenReturn(Optional.of(Account.open("Groceries", AccountType.EXPENSE, Currency.ILS)));

        assertThatThrownBy(
                        () -> service.open(new OpenAccountCommand("  Groceries  ", AccountType.EXPENSE, Currency.ILS)))
                .isInstanceOf(DuplicateAccountNameException.class);

        verify(accounts).findByName("Groceries");
    }

    @Test
    void Given_two_different_names_When_both_are_opened_Then_both_are_stored() {
        when(accounts.findByName(any())).thenReturn(Optional.empty());

        Account first = service.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.ILS));
        Account second = service.open(new OpenAccountCommand("Salary", AccountType.INCOME, Currency.ILS));

        assertThat(first.id()).isNotEqualTo(second.id());
        verify(accounts).save(first);
        verify(accounts).save(second);
    }

    @Test
    void Given_an_account_is_opened_When_it_succeeds_Then_the_event_carries_what_was_stored() {
        when(accounts.findByName(any())).thenReturn(Optional.empty());

        Account opened = service.open(new OpenAccountCommand(" Rent ", AccountType.EXPENSE, Currency.USD));

        assertThat(events.published())
                .containsExactly(
                        new AccountingEvent.AccountOpened(opened.id(), "Rent", AccountType.EXPENSE, Currency.USD));
    }

    /** A refused command must leave no trace, or the event log stops describing the books. */
    @Test
    void Given_a_duplicate_name_When_refused_Then_nothing_is_stored_and_nothing_is_published() {
        when(accounts.findByName("Cash"))
                .thenReturn(Optional.of(Account.open("Cash", AccountType.ASSET, Currency.ILS)));

        assertThatThrownBy(() -> service.open(new OpenAccountCommand("Cash", AccountType.ASSET, Currency.USD)))
                .isInstanceOf(DuplicateAccountNameException.class);

        verify(accounts, never()).save(any());
        assertThat(events.published()).isEmpty();
    }

    @Test
    void Given_a_blank_name_When_the_command_is_built_Then_it_is_refused_at_the_boundary() {
        assertThatThrownBy(() -> new OpenAccountCommand("   ", AccountType.ASSET, Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        verifyNoInteractions(accounts);
    }

    /**
     * The display form is what a person reads on a report, so it is stored as it was typed. Whether
     * the *lookup* folds case is the store's business, and {@code JdbcAccountRepositoryIT} asserts
     * it against the index that does the folding.
     */
    @Test
    void Given_a_mixed_case_name_When_opened_Then_the_typed_form_is_what_is_stored() {
        when(accounts.findByName(any())).thenReturn(Optional.empty());

        Account opened = service.open(new OpenAccountCommand("Groceries", AccountType.EXPENSE, Currency.ILS));

        assertThat(opened.name()).isEqualTo("Groceries");
        verify(accounts).save(opened);
    }

    /**
     * Listing derives nothing of its own — ordering is the repository's job, per {@code
     * AccountRepository.all}'s Javadoc, and the service's only responsibility is to hand the answer
     * back unchanged.
     */
    @Test
    void Given_accounts_stored_When_listed_Then_they_come_back_in_the_repositorys_order() {
        Account brokerage = Account.open("brokerage", AccountType.ASSET, Currency.ILS);
        Account cash = Account.open("Cash", AccountType.ASSET, Currency.ILS);
        Account savings = Account.open("savings", AccountType.ASSET, Currency.ILS);
        when(accounts.all()).thenReturn(List.of(brokerage, cash, savings));

        assertThat(service.all()).containsExactly(brokerage, cash, savings);
    }
}
