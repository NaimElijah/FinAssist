package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.EntryCurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransactionNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.IllegalTransitionException;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Transaction;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.TransactionState;
import io.github.naimelijah.finassist.ledger.accounting.domain.event.AccountingEvent;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of recording a transaction and moving it through its lifecycle.
 *
 * <p>The rule that matters most here is that an entry must be in its account's currency. It cannot be
 * a domain rule and never could be: an {@code Entry} holds an {@code AccountId}, so checking it needs
 * something that turns one into an {@code Account}. Requirements carried it as TODO for exactly that
 * reason. Before this service existed, a mis-currencied entry was caught much later and much less
 * helpfully, as a {@code CurrencyMismatchException} thrown while summing a balance.
 */
class TransactionServiceTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService accountService = new AccountService(accounts, events);
    private final TransactionService service = new TransactionService(accounts, transactions, events);

    private final Account cash = open("Cash", AccountType.ASSET, Currency.ILS);
    private final Account groceries = open("Groceries", AccountType.EXPENSE, Currency.ILS);

    @Test
    void Given_a_balanced_pair_of_entries_When_recorded_Then_it_is_stored_as_pending() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        assertThat(recorded.state()).isEqualTo(new TransactionState.Pending());
        assertThat(transactions.findById(recorded.id())).contains(recorded);
    }

    @Test
    void Given_an_entry_naming_no_known_account_When_recorded_Then_it_is_refused() {
        AccountId unknown = AccountId.generate();

        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(unknown, ils("12.50")), new Entry(cash.id(), ils("-12.50"))))))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void Given_an_entry_in_the_wrong_currency_for_its_account_When_recorded_Then_it_is_refused() {
        Account dollars = open("US savings", AccountType.ASSET, Currency.USD);

        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Transfer",
                        List.of(
                                new Entry(dollars.id(), Money.of("40.00", Currency.ILS)),
                                new Entry(cash.id(), ils("-40.00"))))))
                .isInstanceOf(EntryCurrencyMismatchException.class)
                .hasMessageContaining("US savings")
                .hasMessageContaining("no conversion");
    }

    /** The balancing rule stays in the aggregate; this only pins that the service does not swallow it. */
    @Test
    void Given_entries_that_do_not_balance_When_recorded_Then_the_domain_refuses_it() {
        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-10.00"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would not balance");
    }

    @Test
    void Given_a_refused_transaction_When_it_throws_Then_nothing_is_stored() {
        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(AccountId.generate(), ils("1.00")), new Entry(cash.id(), ils("-1.00"))))))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(transactions.findByAccount(cash.id())).isEmpty();
        assertThat(events.published()).noneMatch(AccountingEvent.TransactionRecorded.class::isInstance);
    }

    @Test
    void Given_a_recorded_transaction_When_cleared_Then_the_new_state_is_stored() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        Transaction cleared = service.clear(recorded.id(), MARCH_3);

        assertThat(cleared.state()).isEqualTo(new TransactionState.Cleared(MARCH_3));
        assertThat(transactions.findById(recorded.id()).orElseThrow().state())
                .isEqualTo(new TransactionState.Cleared(MARCH_3));
    }

    @Test
    void Given_a_cleared_transaction_When_reconciled_Then_it_is_frozen_in_the_store() {
        Transaction recorded = service.record(groceryPurchase("12.50"));
        service.clear(recorded.id(), MARCH_3);

        Transaction reconciled = service.reconcile(recorded.id(), MARCH_5);

        assertThat(reconciled.state()).isEqualTo(new TransactionState.Reconciled(MARCH_5));
        assertThatThrownBy(() -> service.markVoided(recorded.id(), MARCH_5, "changed my mind"))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_a_pending_transaction_When_reconciled_Then_it_is_refused() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        assertThatThrownBy(() -> service.reconcile(recorded.id(), MARCH_3))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_an_unknown_transaction_When_transitioned_Then_it_is_refused() {
        TransactionId unknown = TransactionId.generate();

        assertThatThrownBy(() -> service.clear(unknown, MARCH_3)).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.reconcile(unknown, MARCH_3)).isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.markVoided(unknown, MARCH_3, "typo"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void Given_a_transaction_is_recorded_When_it_succeeds_Then_the_event_names_every_account_it_touches() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        assertThat(events.published())
                .contains(new AccountingEvent.TransactionRecorded(
                        recorded.id(), MARCH_1, "Groceries", List.of(groceries.id(), cash.id())));
    }

    @Test
    void Given_each_transition_When_it_succeeds_Then_its_event_is_published_in_order() {
        Transaction recorded = service.record(groceryPurchase("12.50"));
        service.clear(recorded.id(), MARCH_3);
        service.reconcile(recorded.id(), MARCH_5);

        assertThat(events.published())
                .endsWith(
                        new AccountingEvent.TransactionCleared(recorded.id(), MARCH_3),
                        new AccountingEvent.TransactionReconciled(recorded.id(), MARCH_5));
    }

    /** The stored reason is stripped, so the event must carry the stripped form and not the input. */
    @Test
    void Given_a_padded_void_reason_When_voided_Then_the_event_carries_the_stored_form() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        Transaction voided = service.markVoided(recorded.id(), MARCH_3, "  duplicate entry  ");

        assertThat(voided.state()).isEqualTo(new TransactionState.Voided(MARCH_3, "duplicate entry"));
        assertThat(events.published())
                .contains(new AccountingEvent.TransactionVoided(recorded.id(), MARCH_3, "duplicate entry"));
    }

    private RecordTransactionCommand groceryPurchase(String amount) {
        return new RecordTransactionCommand(
                MARCH_1,
                "Groceries",
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))));
    }

    private Account open(String name, AccountType type, Currency currency) {
        return accountService.open(new OpenAccountCommand(name, type, currency));
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    /**
     * The event documents its accounts as the ones the transaction touches. A payment split across two
     * categories posts twice to the same account, and a subscriber folding over the list - to invalidate
     * a cache, or to count what changed - would count it twice.
     */
    @Test
    void Given_two_entries_against_one_account_When_recorded_Then_the_event_names_it_once() {
        Account rent = open("Rent", AccountType.EXPENSE, Currency.ILS);
        Transaction recorded = service.record(new RecordTransactionCommand(
                MARCH_1,
                "Rent, paid in two parts",
                List.of(
                        new Entry(rent.id(), ils("2000.00")),
                        new Entry(cash.id(), ils("-1500.00")),
                        new Entry(cash.id(), ils("-500.00")))));

        assertThat(events.published())
                .contains(new AccountingEvent.TransactionRecorded(
                        recorded.id(), MARCH_1, "Rent, paid in two parts", List.of(rent.id(), cash.id())));
    }
}
