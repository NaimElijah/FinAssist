package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.accounting.application.port.in.AccountNotFoundException;
import io.github.naimelijah.finassist.ledger.accounting.application.port.in.ViewBalanceUseCase;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountId;
import io.github.naimelijah.finassist.ledger.accounting.domain.entry.Balance;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPosition;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The translation that gives planning an opening balance without either context naming the other.
 *
 * <p>Half of these are about what does <em>not</em> come out. Planning addresses accounts with its
 * own {@link AccountRef} and never learns that accounting uses a UUID, so a bad reference must
 * surface as something planning can name — letting an {@code AccountNotFoundException} through would
 * put an accounting type in planning's call stack, which is the leak the boundary exists to prevent.
 *
 * <p>Driven against a mocked {@code ViewBalanceUseCase}, which is what the translation takes; it
 * never sees a repository, by design (ADR-022). How a balance is summed from entries is
 * {@code JournalServiceTest}'s and {@code BalanceTest}'s business, and an account named twice is
 * refused by planning's commands, in {@code ProjectScenarioCommandTest} and {@code
 * CompareScenariosCommandTest} (ADR-051).
 */
class AccountingOpeningPositionsTest {

    private final ViewBalanceUseCase balances = mock(ViewBalanceUseCase.class);
    private final AccountingOpeningPositions openings = new AccountingOpeningPositions(balances);

    private final AccountRef cash = holding("5000.00", Currency.ILS);
    private final AccountRef investments = holding("20000.00", Currency.ILS);

    @Test
    void Given_named_accounts_When_a_position_is_asked_for_Then_their_balances_are_summed() {
        OpeningPosition position = openings.positionOf(List.of(cash), List.of(investments), Currency.ILS);

        assertThat(position.cash()).isEqualTo(ils("5000.00"));
        assertThat(position.invested()).isEqualTo(ils("20000.00"));
    }

    @Test
    void Given_two_accounts_on_one_side_When_a_position_is_asked_for_Then_they_are_added_together() {
        AccountRef savings = holding("1500.00", Currency.ILS);

        OpeningPosition position = openings.positionOf(List.of(cash, savings), List.of(), Currency.ILS);

        assertThat(position.cash()).isEqualTo(ils("6500.00"));
    }

    @Test
    void Given_no_accounts_named_When_a_position_is_asked_for_Then_that_side_opens_at_zero() {
        OpeningPosition position = openings.positionOf(List.of(), List.of(), Currency.ILS);

        assertThat(position).isEqualTo(OpeningPosition.zero(Currency.ILS));
    }

    @Test
    void Given_a_reference_naming_no_account_When_asked_for_Then_planning_sees_no_accounting_type() {
        AccountId absent = AccountId.generate();
        when(balances.balanceOf(absent)).thenThrow(new AccountNotFoundException(absent));
        AccountRef missing = AccountRef.of(absent.toString());

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
        AccountRef dollars = holding("120.00", Currency.USD);

        assertThatThrownBy(() -> openings.positionOf(List.of(dollars), List.of(), Currency.ILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no conversion");
    }

    /** An account the journal knows about, already carrying a balance. */
    private AccountRef holding(String amount, Currency currency) {
        AccountId id = AccountId.generate();
        when(balances.balanceOf(id)).thenReturn(new Balance(id, Money.of(amount, currency)));
        return AccountRef.of(id.toString());
    }

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }
}
