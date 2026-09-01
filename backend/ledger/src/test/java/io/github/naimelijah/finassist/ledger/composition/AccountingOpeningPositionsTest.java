package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.accounting.adapter.out.event.InMemoryAccountingEvents;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryAccountRepository;
import io.github.naimelijah.finassist.ledger.accounting.adapter.out.persistence.InMemoryTransactionRepository;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.OpenAccountCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.RecordTransactionCommand;
import io.github.naimelijah.finassist.ledger.accounting.application.service.AccountService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.JournalService;
import io.github.naimelijah.finassist.ledger.accounting.application.service.TransactionService;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.Account;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Entry;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPosition;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract of the translation that opens a projection from real balances.
 *
 * <p>Half of these are about what does <em>not</em> come out. Planning addresses accounts with its own
 * {@link AccountRef} and never learns that accounting uses a UUID, so a bad reference must surface as
 * something planning can name — letting an {@code AccountNotFoundException} through would put an
 * accounting type in planning's call stack, which is the leak the boundary exists to prevent.
 */
class AccountingOpeningPositionsTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final InMemoryAccountingEvents events = new InMemoryAccountingEvents();
    private final AccountService accountService = new AccountService(accounts, events);
    private final TransactionService transactionService = new TransactionService(accounts, transactions, events);
    private final AccountingOpeningPositions openings =
            new AccountingOpeningPositions(new JournalService(accounts, transactions));

    private final Account cash = open("Cash", AccountType.ASSET, Currency.ILS);
    private final Account investments = open("Investments", AccountType.ASSET, Currency.ILS);
    private final Account equity = open("Opening balances", AccountType.EQUITY, Currency.ILS);

    @Test
    void Given_named_accounts_When_a_position_is_asked_for_Then_their_balances_are_summed() {
        fund(cash, "5000.00");
        fund(investments, "20000.00");

        OpeningPosition position = openings.positionOf(List.of(ref(cash)), List.of(ref(investments)), Currency.ILS);

        assertThat(position.cash()).isEqualTo(ils("5000.00"));
        assertThat(position.invested()).isEqualTo(ils("20000.00"));
    }

    @Test
    void Given_two_accounts_on_one_side_When_a_position_is_asked_for_Then_they_are_added_together() {
        Account savings = open("Savings", AccountType.ASSET, Currency.ILS);
        fund(cash, "5000.00");
        fund(savings, "1500.00");

        OpeningPosition position = openings.positionOf(List.of(ref(cash), ref(savings)), List.of(), Currency.ILS);

        assertThat(position.cash()).isEqualTo(ils("6500.00"));
    }

    @Test
    void Given_no_accounts_named_When_a_position_is_asked_for_Then_that_side_opens_at_zero() {
        OpeningPosition position = openings.positionOf(List.of(), List.of(), Currency.ILS);

        assertThat(position).isEqualTo(OpeningPosition.zero(Currency.ILS));
    }

    @Test
    void Given_a_reference_naming_no_account_When_asked_for_Then_planning_sees_no_accounting_type() {
        AccountRef missing = AccountRef.of(AccountId.generate().toString());

        assertThatThrownBy(() -> openings.positionOf(List.of(missing), List.of(), Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("names no account");
    }

    @Test
    void Given_a_reference_that_is_not_an_identifier_When_asked_for_Then_it_is_refused_by_name() {
        assertThatThrownBy(
                        () -> openings.positionOf(List.of(AccountRef.of("current-account")), List.of(), Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an account identifier");
    }

    @Test
    void Given_an_account_in_another_currency_When_asked_for_shekels_Then_it_is_refused() {
        Account dollars = open("US savings", AccountType.ASSET, Currency.USD);

        assertThatThrownBy(() -> openings.positionOf(List.of(ref(dollars)), List.of(), Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no conversion");
    }

    private AccountRef ref(Account account) {
        return AccountRef.of(account.id().toString());
    }

    private void fund(Account account, String amount) {
        transactionService.record(new RecordTransactionCommand(
                JAN_1,
                "Opening balance",
                List.of(new Entry(account.id(), ils(amount)), new Entry(equity.id(), ils("-" + amount)))));
    }

    private Account open(String name, AccountType type, Currency currency) {
        return accountService.open(new OpenAccountCommand(name, type, currency));
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
