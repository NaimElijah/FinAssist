package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import java.util.List;
import java.util.Objects;

/**
 * What a caller must supply to compare strategies.
 *
 * <p>One horizon, one cadence and one opening position for all of them. {@code Comparison} refuses
 * projections that disagree on any of those rather than aligning them approximately, so offering a
 * horizon per scenario would only create a way to fail.
 */
public record CompareScenariosCommand(
        List<ScenarioId> scenarios,
        Horizon horizon,
        Cadence step,
        List<AccountRef> cashAccounts,
        List<AccountRef> investedAccounts) {

    public CompareScenariosCommand {
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(step, "step");
        scenarios = List.copyOf(scenarios);
        cashAccounts = List.copyOf(cashAccounts);
        investedAccounts = List.copyOf(investedAccounts);
    }
}
