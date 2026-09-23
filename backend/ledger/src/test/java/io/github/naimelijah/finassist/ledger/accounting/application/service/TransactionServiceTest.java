package io.github.naimelijah.finassist.ledger.accounting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.Actor;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.EntryCurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.TransactionNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.AccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.out.TransactionRepository;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The contract of recording a transaction and moving it through its lifecycle.
 *
 * <p>The rule that matters most here is that an entry must be in its account's currency. It cannot
 * be a domain rule and never could be: an {@code Entry} holds an {@code AccountId}, so checking it
 * needs something that turns one into an {@code Account}. Requirements carried it as TODO for
 * exactly that reason. Before this service existed, a mis-currencied entry was caught much later and
 * much less helpfully, as a {@code CurrencyMismatchException} thrown while summing a balance.
 *
 * <p>Both ports are mocked, so what is asserted is what the service does: which accounts it loads,
 * what it hands the store, and what it publishes. That the store then keeps the journal append-only
 * is a different claim, tested against a real database in {@code JdbcTransactionRepositoryIT}.
 */
class TransactionServiceTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_3 = LocalDate.of(2026, 3, 3);
    private static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    private static final Actor RECORDER = new Actor("owner-user");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final TransactionService service = new TransactionService(accounts, transactions, events);

    private final Account cash = known("Cash", AccountType.ASSET, Currency.ILS);
    private final Account groceries = known("Groceries", AccountType.EXPENSE, Currency.ILS);

    @Test
    void Given_a_balanced_pair_of_entries_When_recorded_Then_it_is_stored_as_pending() {
        Transaction recorded = service.record(groceryPurchase("12.50"));

        assertThat(recorded.state()).isEqualTo(new TransactionState.Pending());
        verify(transactions).save(recorded, RECORDER);
    }

    @Test
    void Given_an_entry_naming_no_known_account_When_recorded_Then_it_is_refused() {
        AccountId unknown = AccountId.generate();
        when(accounts.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(unknown, ils("12.50")), new Entry(cash.id(), ils("-12.50"))),
                        RECORDER)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void Given_an_entry_in_the_wrong_currency_for_its_account_When_recorded_Then_it_is_refused() {
        Account dollars = known("US savings", AccountType.ASSET, Currency.USD);

        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Transfer",
                        List.of(
                                new Entry(dollars.id(), Money.of("40.00", Currency.ILS)),
                                new Entry(cash.id(), ils("-40.00"))),
                        RECORDER)))
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
                        List.of(new Entry(groceries.id(), ils("12.50")), new Entry(cash.id(), ils("-10.00"))),
                        RECORDER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would not balance");
    }

    @Test
    void Given_a_refused_transaction_When_it_throws_Then_nothing_is_stored() {
        AccountId unknown = AccountId.generate();
        when(accounts.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(new RecordTransactionCommand(
                        MARCH_1,
                        "Groceries",
                        List.of(new Entry(unknown, ils("1.00")), new Entry(cash.id(), ils("-1.00"))),
                        RECORDER)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transactions, never()).save(any(), any());
        assertThat(events.published()).noneMatch(AccountingEvent.TransactionRecorded.class::isInstance);
    }

    @Test
    void Given_a_recorded_transaction_When_cleared_Then_the_new_state_is_stored() {
        Transaction recorded = stored(service.record(groceryPurchase("12.50")));

        Transaction cleared = service.clear(recorded.id(), MARCH_3, RECORDER);

        assertThat(cleared.state()).isEqualTo(new TransactionState.Cleared(MARCH_3));
        verify(transactions).save(cleared, RECORDER);
    }

    @Test
    void Given_a_cleared_transaction_When_reconciled_Then_it_is_frozen() {
        Transaction recorded = stored(service.record(groceryPurchase("12.50")));
        Transaction cleared = stored(service.clear(recorded.id(), MARCH_3, RECORDER));

        Transaction reconciled = stored(service.reconcile(cleared.id(), MARCH_5, RECORDER));

        assertThat(reconciled.state()).isEqualTo(new TransactionState.Reconciled(MARCH_5));
        assertThatThrownBy(() -> service.markVoided(recorded.id(), MARCH_5, "changed my mind", RECORDER))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_a_pending_transaction_When_reconciled_Then_it_is_refused() {
        Transaction recorded = stored(service.record(groceryPurchase("12.50")));

        assertThatThrownBy(() -> service.reconcile(recorded.id(), MARCH_3, RECORDER))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_an_unknown_transaction_When_transitioned_Then_it_is_refused() {
        TransactionId unknown = TransactionId.generate();
        when(transactions.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clear(unknown, MARCH_3, RECORDER))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.reconcile(unknown, MARCH_3, RECORDER))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThatThrownBy(() -> service.markVoided(unknown, MARCH_3, "typo", RECORDER))
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
        Transaction recorded = stored(service.record(groceryPurchase("12.50")));
        Transaction cleared = stored(service.clear(recorded.id(), MARCH_3, RECORDER));
        service.reconcile(cleared.id(), MARCH_5, RECORDER);

        assertThat(events.published())
                .endsWith(
                        new AccountingEvent.TransactionCleared(recorded.id(), MARCH_3),
                        new AccountingEvent.TransactionReconciled(recorded.id(), MARCH_5));
    }

    /** The stored reason is stripped, so the event must carry the stripped form and not the input. */
    @Test
    void Given_a_padded_void_reason_When_voided_Then_the_event_carries_the_stored_form() {
        Transaction recorded = stored(service.record(groceryPurchase("12.50")));

        Transaction voided = service.markVoided(recorded.id(), MARCH_3, "  duplicate entry  ", RECORDER);

        assertThat(voided.state()).isEqualTo(new TransactionState.Voided(MARCH_3, "duplicate entry"));
        assertThat(events.published())
                .contains(new AccountingEvent.TransactionVoided(recorded.id(), MARCH_3, "duplicate entry"));
    }

    /**
     * Each transition can be made by someone other than whoever recorded the transaction — a clerk
     * records it, an auditor reconciles it — and the store must be told each writer, not only the
     * first one (ADR-033).
     */
    @Test
    void
            Given_different_actors_When_a_transaction_is_recorded_cleared_and_reconciled_Then_each_write_names_its_own_actor() {
        Actor recorder = new Actor("owner-user");
        Actor clearer = new Actor("clerk-user");
        Actor reconciler = new Actor("auditor-user");
        Transaction recorded = stored(service.record(groceryPurchase("12.50", recorder)));

        Transaction cleared = stored(service.clear(recorded.id(), MARCH_3, clearer));
        Transaction reconciled = service.reconcile(cleared.id(), MARCH_5, reconciler);

        verify(transactions).save(recorded, recorder);
        verify(transactions).save(cleared, clearer);
        verify(transactions).save(reconciled, reconciler);
    }

    /** Voiding is a transition like the others: the store is told whoever voided it, not whoever recorded it. */
    @Test
    void Given_a_different_actor_When_a_pending_transaction_is_voided_Then_the_store_is_told_that_writer() {
        Actor recorder = new Actor("owner-user");
        Actor voider = new Actor("viewer-user");
        Transaction recorded = stored(service.record(groceryPurchase("12.50", recorder)));

        Transaction voided = service.markVoided(recorded.id(), MARCH_3, "duplicate", voider);

        verify(transactions).save(voided, voider);
    }

    /**
     * The event documents its accounts as the ones the transaction touches. A payment split across
     * two categories posts twice to the same account, and a subscriber folding over the list - to
     * invalidate a cache, or to count what changed - would count it twice.
     */
    @Test
    void Given_two_entries_against_one_account_When_recorded_Then_the_event_names_it_once() {
        Account rent = known("Rent", AccountType.EXPENSE, Currency.ILS);
        Transaction recorded = service.record(new RecordTransactionCommand(
                MARCH_1,
                "Rent, paid in two parts",
                List.of(
                        new Entry(rent.id(), ils("2000.00")),
                        new Entry(cash.id(), ils("-1500.00")),
                        new Entry(cash.id(), ils("-500.00"))),
                RECORDER));

        assertThat(events.published())
                .contains(new AccountingEvent.TransactionRecorded(
                        recorded.id(), MARCH_1, "Rent, paid in two parts", List.of(rent.id(), cash.id())));
    }

    private RecordTransactionCommand groceryPurchase(String amount) {
        return groceryPurchase(amount, RECORDER);
    }

    private RecordTransactionCommand groceryPurchase(String amount, Actor recordedBy) {
        return new RecordTransactionCommand(
                MARCH_1,
                "Groceries",
                List.of(new Entry(groceries.id(), ils(amount)), new Entry(cash.id(), ils("-" + amount))),
                recordedBy);
    }

    /** An account the store knows about, so the service can resolve it and read its currency. */
    private Account known(String name, AccountType type, Currency currency) {
        Account account = Account.open(name, type, currency);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        return account;
    }

    /**
     * What the store would hand back on the next read.
     *
     * <p>The transitions are a read-modify-write, so a test that exercises two in sequence has to say
     * what the first one left behind. Stating it is the point: with a stateful fake this coupling was
     * real and invisible, and the second transition passed because of a side effect nobody wrote down.
     */
    private Transaction stored(Transaction transaction) {
        when(transactions.findById(transaction.id())).thenReturn(Optional.of(transaction));
        return transaction;
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
