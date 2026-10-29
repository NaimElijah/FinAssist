package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which accounts a projection opens from, as planning itself refuses them.
 *
 * <p>Counting an account twice doubles the opening balance, and every point, the end state and the
 * shortfall date would then be wrong with nothing to say so. The rule speaks only of planning's own
 * {@link AccountRef}, so planning holds it rather than the translation that reads the balances
 * (ADR-051). {@code CompareScenariosCommandTest} pins the same rule for the other entrance.
 */
class ProjectScenarioCommandTest {

    private static final Horizon YEAR_2026 = new Horizon(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

    private static final AccountRef CURRENT = AccountRef.of("current");
    private static final AccountRef SAVINGS = AccountRef.of("savings");
    private static final AccountRef PENSION = AccountRef.of("pension");

    @Test
    void Given_each_account_named_once_When_the_command_is_built_Then_both_lists_are_kept_in_order() {
        ProjectScenarioCommand command = command(List.of(CURRENT, SAVINGS), List.of(PENSION));

        assertThat(command.cashAccounts()).containsExactly(CURRENT, SAVINGS);
        assertThat(command.investedAccounts()).containsExactly(PENSION);
    }

    /** An easy slip when a caller concatenates two lists of references, and a silent one. */
    @Test
    void Given_an_account_named_twice_as_cash_When_the_command_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> command(List.of(CURRENT, SAVINGS, CURRENT), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account reference current is named more than once; it would be counted twice");
    }

    @Test
    void Given_an_account_named_twice_as_invested_When_the_command_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> command(List.of(), List.of(PENSION, PENSION)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account reference pension is named more than once; it would be counted twice");
    }

    /** An account is cash or it is invested, never both. */
    @Test
    void Given_an_account_named_on_both_sides_When_the_command_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> command(List.of(SAVINGS), List.of(PENSION, SAVINGS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account reference savings is named more than once; it would be counted twice");
    }

    private static ProjectScenarioCommand command(List<AccountRef> cash, List<AccountRef> invested) {
        return new ProjectScenarioCommand(ScenarioId.generate(), YEAR_2026, Cadence.MONTHLY, cash, invested);
    }
}
