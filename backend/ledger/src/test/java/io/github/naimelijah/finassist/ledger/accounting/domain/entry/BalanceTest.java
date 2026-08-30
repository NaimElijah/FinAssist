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

/** A balance is derived from entries and never stored; voided transactions do not count. */
class BalanceTest {

    private static final LocalDate WHEN = LocalDate.of(2026, 3, 1);
    private static final AccountId CHECKING = AccountId.generate();
    private static final AccountId EXPENSE = AccountId.generate();

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static Transaction spend(String amount) {
        return Transaction.record(
                WHEN,
                "Spend " + amount,
                List.of(new Entry(EXPENSE, ils(amount)), new Entry(CHECKING, ils("-" + amount))));
    }

    @Test
    void Given_no_transactions_When_a_balance_is_taken_Then_it_is_zero_in_the_account_currency() {
        Balance balance = Balance.of(CHECKING, Currency.ILS, List.of());
        assertThat(balance.amount()).isEqualTo(Money.zero(Currency.ILS));
        assertThat(balance.account()).isEqualTo(CHECKING);
    }

    @Test
    void Given_several_transactions_When_a_balance_is_taken_Then_the_entries_are_summed() {
        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(spend("12.50"), spend("7.50")))
                        .amount())
                .isEqualTo(ils("-20.00"));
    }

    @Test
    void Given_a_voided_transaction_When_a_balance_is_taken_Then_it_does_not_count() {
        Transaction voided = spend("100.00").markVoided(WHEN, "duplicate");
        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(spend("12.50"), voided))
                        .amount())
                .isEqualTo(ils("-12.50"));
    }

    @Test
    void Given_the_two_sides_of_a_transaction_When_both_balances_are_taken_Then_they_cancel() {
        List<Transaction> book = List.of(spend("12.50"), spend("7.50"));
        Money checking = Balance.of(CHECKING, Currency.ILS, book).amount();
        Money expense = Balance.of(EXPENSE, Currency.ILS, book).amount();
        assertThat(checking.plus(expense)).isEqualTo(Money.zero(Currency.ILS));
    }

    @Test
    void Given_a_book_holding_two_currencies_When_a_balance_is_taken_Then_the_other_is_skipped() {
        AccountId dollarAccount = AccountId.generate();
        AccountId dollarExpense = AccountId.generate();
        Transaction inDollars = Transaction.record(
                WHEN,
                "Spend in USD",
                List.of(
                        new Entry(dollarExpense, Money.of("30.00", Currency.USD)),
                        new Entry(dollarAccount, Money.of("-30.00", Currency.USD))));

        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(spend("12.50"), inDollars))
                        .amount())
                .isEqualTo(ils("-12.50"));
        assertThat(Balance.of(dollarAccount, Currency.USD, List.of(spend("12.50"), inDollars))
                        .amount())
                .isEqualTo(Money.of("-30.00", Currency.USD));
    }

    @Test
    void Given_a_reconciled_transaction_When_a_balance_is_taken_Then_it_still_counts() {
        Transaction reconciled = spend("12.50").clear(WHEN).reconcile(WHEN);
        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(reconciled)).amount())
                .isEqualTo(ils("-12.50"));
    }

    /**
     * The failure path {@link Balance} documents and nothing exercised. A book holding two
     * currencies is fine — those transactions are skipped — but an entry posted <em>to this
     * account</em> in another currency is a data error, and summing it must fail rather than
     * quietly produce a figure in the wrong denomination.
     */
    @Test
    void Given_a_foreign_entry_posted_to_the_account_When_a_balance_is_taken_Then_it_is_refused() {
        Transaction misposted = Transaction.record(
                WHEN,
                "Posted to an ILS account in USD",
                List.of(
                        new Entry(EXPENSE, Money.of("30.00", Currency.USD)),
                        new Entry(CHECKING, Money.of("-30.00", Currency.USD))));

        assertThatThrownBy(() -> Balance.of(CHECKING, Currency.ILS, List.of(misposted)))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("no conversion");
    }

    @Test
    void Given_two_entries_on_one_account_in_a_transaction_When_a_balance_is_taken_Then_both_count() {
        AccountId groceries = AccountId.generate();
        Transaction split = Transaction.record(
                WHEN,
                "Two lines against the same account",
                List.of(
                        new Entry(groceries, ils("10.00")),
                        new Entry(groceries, ils("5.00")),
                        new Entry(CHECKING, ils("-15.00"))));

        assertThat(Balance.of(groceries, Currency.ILS, List.of(split)).amount()).isEqualTo(ils("15.00"));
    }

    @Test
    void Given_a_pending_transaction_When_a_balance_is_taken_Then_it_already_counts() {
        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(spend("12.50"))).amount())
                .isEqualTo(ils("-12.50"));
        assertThat(Balance.of(CHECKING, Currency.ILS, List.of(spend("12.50").clear(WHEN)))
                        .amount())
                .isEqualTo(ils("-12.50"));
    }
}
