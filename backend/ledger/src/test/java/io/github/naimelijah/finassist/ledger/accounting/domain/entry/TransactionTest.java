package io.github.naimelijah.finassist.ledger.accounting.domain.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The balancing invariant and the transaction lifecycle (ADR-011). */
class TransactionTest {

    private static final LocalDate WHEN = LocalDate.of(2026, 3, 1);
    private static final AccountId CHECKING = AccountId.generate();
    private static final AccountId COFFEE = AccountId.generate();

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static Transaction coffee() {
        return Transaction.record(
                WHEN, "Coffee", List.of(new Entry(COFFEE, ils("12.50")), new Entry(CHECKING, ils("-12.50"))));
    }

    @Test
    void Given_entries_that_sum_to_zero_When_recorded_Then_the_transaction_is_created_pending() {
        Transaction transaction = coffee();
        assertThat(transaction.state()).isInstanceOf(TransactionState.Pending.class);
        assertThat(transaction.entries()).hasSize(2);
        assertThat(transaction.currency()).isEqualTo(Currency.ILS);
    }

    @Test
    void Given_entries_that_do_not_sum_to_zero_When_recorded_Then_it_cannot_be_created() {
        assertThatThrownBy(() -> Transaction.record(
                        WHEN,
                        "Lost money",
                        List.of(new Entry(COFFEE, ils("12.50")), new Entry(CHECKING, ils("-12.00")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would not balance");
    }

    @Test
    void Given_a_single_entry_When_recorded_Then_it_is_refused() {
        assertThatThrownBy(() -> Transaction.record(WHEN, "Half a story", List.of(new Entry(COFFEE, ils("12.50")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two entries");
    }

    @Test
    void Given_entries_in_different_currencies_When_recorded_Then_it_is_refused() {
        assertThatThrownBy(() -> Transaction.record(
                        WHEN,
                        "Cross currency",
                        List.of(
                                new Entry(COFFEE, ils("12.50")),
                                new Entry(CHECKING, Money.of("-12.50", Currency.USD)))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void Given_an_entry_of_zero_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Entry(COFFEE, Money.zero(Currency.ILS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("records nothing");
    }

    @Test
    void Given_a_blank_description_When_recorded_Then_it_is_refused() {
        assertThatThrownBy(() -> Transaction.record(
                        WHEN, "   ", List.of(new Entry(COFFEE, ils("1.00")), new Entry(CHECKING, ils("-1.00")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Given_a_pending_transaction_When_cleared_and_reconciled_Then_both_transitions_are_allowed() {
        Transaction reconciled = coffee().clear(WHEN.plusDays(1)).reconcile(WHEN.plusDays(5));
        assertThat(reconciled.state()).isInstanceOf(TransactionState.Reconciled.class);
    }

    @Test
    void Given_a_pending_transaction_When_reconciled_directly_Then_it_is_refused() {
        assertThatThrownBy(() -> coffee().reconcile(WHEN))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Pending");
    }

    @Test
    void Given_a_cleared_transaction_When_cleared_again_Then_it_is_refused() {
        Transaction cleared = coffee().clear(WHEN);
        assertThatThrownBy(() -> cleared.clear(WHEN)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_a_reconciled_transaction_When_voided_Then_it_is_refused_because_it_is_frozen() {
        Transaction reconciled = coffee().clear(WHEN).reconcile(WHEN);
        assertThatThrownBy(() -> reconciled.markVoided(WHEN, "mistake"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Reconciled");
    }

    @Test
    void Given_a_voided_transaction_When_voided_again_Then_it_is_refused() {
        Transaction voided = coffee().markVoided(WHEN, "duplicate");
        assertThatThrownBy(() -> voided.markVoided(WHEN, "again")).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void Given_a_void_without_a_reason_When_applied_Then_it_is_refused() {
        assertThatThrownBy(() -> coffee().markVoided(WHEN, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void Given_a_clearing_date_before_the_transaction_When_cleared_Then_it_is_refused() {
        assertThatThrownBy(() -> coffee().clear(WHEN.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it occurred");
    }

    @Test
    void Given_a_reconciliation_before_the_clearing_date_When_reconciled_Then_it_is_refused() {
        Transaction cleared = coffee().clear(WHEN.plusDays(3));
        assertThatThrownBy(() -> cleared.reconcile(WHEN.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it cleared");
    }

    @Test
    void Given_a_void_date_before_the_transaction_When_voided_Then_it_is_refused() {
        assertThatThrownBy(() -> coffee().markVoided(WHEN.minusDays(1), "mistake"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it occurred");
    }

    @Test
    void Given_dates_on_the_same_day_When_transitioned_Then_they_are_allowed() {
        assertThat(coffee().clear(WHEN).reconcile(WHEN).state()).isInstanceOf(TransactionState.Reconciled.class);
    }

    @Test
    void Given_a_transaction_When_a_transition_is_applied_Then_the_original_is_unchanged() {
        Transaction pending = coffee();
        Transaction cleared = pending.clear(WHEN);
        assertThat(pending.state()).isInstanceOf(TransactionState.Pending.class);
        assertThat(cleared.state()).isInstanceOf(TransactionState.Cleared.class);
        assertThat(cleared.id()).isEqualTo(pending.id());
    }

    @Test
    void Given_a_transaction_When_asked_for_an_untouched_account_Then_the_net_is_zero() {
        assertThat(coffee().netFor(AccountId.generate())).isEqualTo(Money.zero(Currency.ILS));
        assertThat(coffee().netFor(CHECKING)).isEqualTo(ils("-12.50"));
    }

    @Test
    void Given_entries_are_given_as_a_mutable_list_When_recorded_Then_the_aggregate_is_unaffected() {
        List<Entry> mutable =
                new java.util.ArrayList<>(List.of(new Entry(COFFEE, ils("12.50")), new Entry(CHECKING, ils("-12.50"))));
        Transaction transaction = Transaction.record(WHEN, "Coffee", mutable);
        mutable.clear();
        assertThat(transaction.entries()).hasSize(2);
    }
}
