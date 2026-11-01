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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /**
     * The state is checked before the date, as {@code clear} and {@code reconcile} do: a void that
     * is illegal from this state is an {@link IllegalTransitionException}, whatever date it names,
     * never an {@link IllegalArgumentException} about the date. The web layer maps the first to 409
     * and the second to 422.
     */
    @Test
    void Given_a_reconciled_transaction_When_voided_before_it_occurred_Then_the_transition_is_refused() {
        Transaction reconciled = coffee().clear(WHEN).reconcile(WHEN);

        assertThatThrownBy(() -> reconciled.markVoided(WHEN.minusDays(1), "mistake"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Reconciled");
    }

    /** A missing date is checked after the state too: a frozen transaction refuses the void itself. */
    @Test
    void Given_a_reconciled_transaction_When_voided_with_no_date_Then_the_transition_is_refused() {
        Transaction reconciled = coffee().clear(WHEN).reconcile(WHEN);

        assertThatThrownBy(() -> reconciled.markVoided(null, "mistake"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Reconciled");
    }

    @Test
    void Given_a_voided_transaction_When_voided_again_before_it_occurred_Then_the_transition_is_refused() {
        Transaction voided = coffee().markVoided(WHEN, "duplicate");

        assertThatThrownBy(() -> voided.markVoided(WHEN.minusDays(1), "again"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Voided");
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

    @Test
    void Given_a_cleared_transaction_When_voided_Then_it_keeps_its_entries_and_stops_counting() {
        Transaction voided = coffee().clear(WHEN).markVoided(WHEN, "Charged twice");

        assertThat(voided.state()).isInstanceOf(TransactionState.Voided.class);
        assertThat(voided.countsTowardsBalance()).isFalse();
        assertThat(voided.entries()).hasSize(2);
    }

    /**
     * Every command from every state, on the aggregate itself: the four legal transitions reach the
     * state they name, and the other eight are refused with {@link IllegalTransitionException} naming
     * the command and the state. {@code Reconciled} refusing all three is the freeze ADR-011 asks for,
     * held where it is enforced — {@link Transaction}'s own guards — rather than by the absence of a
     * method on the state type, which {@link Transaction} never calls for a frozen state.
     */
    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
        "Pending,    clear,     Cleared",
        "Pending,    reconcile, refused",
        "Pending,    void,      Voided",
        "Cleared,    clear,     refused",
        "Cleared,    reconcile, Reconciled",
        "Cleared,    void,      Voided",
        "Reconciled, clear,     refused",
        "Reconciled, reconcile, refused",
        "Reconciled, void,      refused",
        "Voided,     clear,     refused",
        "Voided,     reconcile, refused",
        "Voided,     void,      refused"
    })
    void Given_a_transaction_in_a_state_When_a_command_is_applied_Then_only_the_legal_transitions_succeed(
            String from, String command, String outcome) {
        Transaction transaction = inState(from);

        if (outcome.equals("refused")) {
            assertThatThrownBy(() -> apply(command, transaction))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessage("Cannot " + command + " a transaction that is " + from);
        } else {
            assertThat(apply(command, transaction).state().name()).isEqualTo(outcome);
        }
    }

    private static Transaction inState(String state) {
        return switch (state) {
            case "Pending" -> coffee();
            case "Cleared" -> coffee().clear(WHEN);
            case "Reconciled" -> coffee().clear(WHEN).reconcile(WHEN);
            case "Voided" -> coffee().markVoided(WHEN, "duplicate");
            default -> throw new IllegalArgumentException("no such state in this matrix: " + state);
        };
    }

    /** Every command on the same day as the transaction, so no date rule can be what refuses it. */
    private static Transaction apply(String command, Transaction transaction) {
        return switch (command) {
            case "clear" -> transaction.clear(WHEN);
            case "reconcile" -> transaction.reconcile(WHEN);
            case "void" -> transaction.markVoided(WHEN, "mistake");
            default -> throw new IllegalArgumentException("no such command in this matrix: " + command);
        };
    }

    @Test
    void Given_three_entries_that_balance_When_recorded_Then_a_split_transaction_is_created() {
        AccountId groceries = AccountId.generate();
        Transaction split = Transaction.record(
                WHEN,
                "Groceries and coffee on one receipt",
                List.of(
                        new Entry(groceries, ils("20.00")),
                        new Entry(COFFEE, ils("12.50")),
                        new Entry(CHECKING, ils("-32.50"))));

        assertThat(split.entries()).hasSize(3);
        assertThat(split.netFor(CHECKING)).isEqualTo(ils("-32.50"));
    }

    @Test
    void Given_a_description_padded_with_spaces_When_recorded_Then_it_is_stripped() {
        assertThat(Transaction.record(
                                WHEN,
                                "  Coffee  ",
                                List.of(new Entry(COFFEE, ils("12.50")), new Entry(CHECKING, ils("-12.50"))))
                        .description())
                .isEqualTo("Coffee");
    }

    /**
     * A void reverses the clearing, so it cannot predate it. {@code reconcile} already guards
     * against its own predecessor this way; {@code markVoided} compared only against
     * {@code occurredOn}, which let a transaction cleared on the 20th be voided on the 10th.
     */
    @Test
    void Given_a_cleared_transaction_When_voided_before_it_cleared_Then_it_is_refused() {
        Transaction cleared = coffee().clear(LocalDate.of(2026, 3, 20));

        assertThatThrownBy(() -> cleared.markVoided(LocalDate.of(2026, 3, 10), "Charged twice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleared");
    }

    @Test
    void Given_a_cleared_transaction_When_voided_on_the_day_it_cleared_Then_it_is_allowed() {
        LocalDate clearedOn = LocalDate.of(2026, 3, 20);
        Transaction cleared = coffee().clear(clearedOn);

        assertThat(cleared.markVoided(clearedOn, "Charged twice").countsTowardsBalance())
                .isFalse();
    }

    @Test
    void Given_a_transaction_id_When_rendered_and_parsed_Then_the_round_trip_holds() {
        TransactionId id = TransactionId.generate();

        assertThat(TransactionId.of(id.toString())).isEqualTo(id);
        assertThat(id.toString()).isEqualTo(id.value().toString());
    }

    @Test
    void Given_text_that_is_not_a_transaction_id_When_parsed_Then_it_is_refused() {
        assertThatThrownBy(() -> TransactionId.of("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Given_each_transaction_state_When_named_Then_the_name_is_the_one_used_in_refusals() {
        assertThat(new TransactionState.Pending().name()).isEqualTo("Pending");
        assertThat(new TransactionState.Cleared(WHEN).name()).isEqualTo("Cleared");
        assertThat(new TransactionState.Reconciled(WHEN).name()).isEqualTo("Reconciled");
        assertThat(new TransactionState.Voided(WHEN, "Charged twice").name()).isEqualTo("Voided");
    }
}
