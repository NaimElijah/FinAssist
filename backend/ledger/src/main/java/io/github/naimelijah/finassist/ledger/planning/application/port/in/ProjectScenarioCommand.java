package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import java.util.List;
import java.util.Objects;

/**
 * What a caller must supply to project a strategy forward.
 *
 * <p><strong>The caller names which accounts are cash and which are investments.</strong> Nothing in
 * FinAssist says an {@code ASSET} account of one name is cash and another is an investment, and
 * inventing that rule to save the caller two lists would bury a policy nobody decided inside a
 * service. Either list may be empty, which opens that line at zero. An account named twice, on one
 * side or across both, is refused here, since it would be counted twice (ADR-051).
 *
 * <p>{@link AccountRef} lives in {@code port.out} because the port that consumes it does. It appears
 * here because the caller is who knows which accounts it means.
 */
public record ProjectScenarioCommand(
        ScenarioId scenario,
        Horizon horizon,
        Cadence step,
        List<AccountRef> cashAccounts,
        List<AccountRef> investedAccounts) {

    public ProjectScenarioCommand {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(step, "step");
        cashAccounts = List.copyOf(cashAccounts);
        investedAccounts = List.copyOf(investedAccounts);
        AccountSelection.requireEachNamedOnce(cashAccounts, investedAccounts);
    }
}
