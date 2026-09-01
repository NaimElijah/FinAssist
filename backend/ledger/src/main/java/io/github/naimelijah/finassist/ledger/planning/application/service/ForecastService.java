package io.github.naimelijah.finassist.ledger.planning.application.service;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ScenarioNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPosition;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.OpeningPositionProvider;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.ScenarioRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects strategies forward, and puts them side by side.
 *
 * <p>Derives everything and stores nothing, exactly as {@code Projection} and {@code Comparison} do.
 *
 * <p><strong>The flows are re-read before every projection.</strong> A {@code Scenario} holds its
 * flows by value, so the copy stored when it was defined has whatever lifecycle state they had then.
 * Trusting that would make {@code PlannedFlow.countsTowardsProjection} unreachable in practice — a
 * plan cancelled last week would still be forecast — which is a domain rule quietly doing nothing.
 * The scenario's identity, name, currency and growth are carried over unchanged; only the flows are
 * refreshed.
 */
public class ForecastService implements ProjectScenarioUseCase, CompareScenariosUseCase {

    private final ScenarioRepository scenarios;
    private final PlannedFlowRepository flows;
    private final OpeningPositionProvider positions;

    public ForecastService(
            ScenarioRepository scenarios, PlannedFlowRepository flows, OpeningPositionProvider positions) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.flows = Objects.requireNonNull(flows, "flows");
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    @Override
    public Projection project(ProjectScenarioCommand command) {
        Objects.requireNonNull(command, "command");
        Scenario scenario = current(command.scenario());
        OpeningPosition opening = openingFor(command.cashAccounts(), command.investedAccounts(), scenario.currency());
        return Projection.of(scenario, command.horizon(), command.step(), opening.cash(), opening.invested());
    }

    @Override
    public Comparison compare(CompareScenariosCommand command) {
        Objects.requireNonNull(command, "command");

        // Memoised per currency. The arguments are identical for every scenario, and behind the
        // translation each call re-scans the journal once per account named.
        Map<Currency, OpeningPosition> opened = new HashMap<>();
        Map<String, Projection> projections = new LinkedHashMap<>();

        for (ScenarioId id : command.scenarios()) {
            Scenario scenario = current(id);
            OpeningPosition opening = opened.computeIfAbsent(
                    scenario.currency(),
                    currency -> openingFor(command.cashAccounts(), command.investedAccounts(), currency));

            Projection projection =
                    Projection.of(scenario, command.horizon(), command.step(), opening.cash(), opening.invested());

            // Checked here rather than left to Comparison, which never gets the chance: keying a map by
            // name means the second scenario silently replaces the first, so a comparison of two
            // same-named strategies would come back describing only one of them, and a caller comparing
            // exactly two would be told it needs at least two.
            if (projections.put(scenario.name(), projection) != null) {
                throw new IllegalArgumentException(
                        "Two scenarios in this comparison are both named '" + scenario.name() + "'");
            }
        }
        // Comparison refuses fewer than two, and projections that disagree. Neither is re-checked here.
        return Comparison.of(projections);
    }

    private OpeningPosition openingFor(List<AccountRef> cash, List<AccountRef> invested, Currency currency) {
        return requireScenarioCurrency(positions.positionOf(cash, invested, currency), currency);
    }

    /** The scenario as it stands now, rather than as it was when somebody named it. */
    private Scenario current(ScenarioId id) {
        Objects.requireNonNull(id, "id");
        Scenario stored = scenarios.findById(id).orElseThrow(() -> new ScenarioNotFoundException(id));

        List<PlannedFlow> refreshed = stored.flows().stream()
                .map(flow -> flows.findById(flow.id()).orElseThrow(() -> new PlanNotFoundException(flow.id())))
                .toList();

        return new Scenario(stored.id(), stored.name(), stored.currency(), refreshed, stored.growth());
    }

    /**
     * The port promises the currency it was asked for. {@code Projection.of} would refuse a mismatch
     * anyway, but it would name the projection rather than the port that produced the wrong figure.
     */
    private static OpeningPosition requireScenarioCurrency(OpeningPosition opening, Currency currency) {
        if (opening.currency() != currency) {
            throw new IllegalStateException("OpeningPositionProvider returned " + opening.currency()
                    + " for a scenario in " + currency + "; it was asked for " + currency);
        }
        return opening;
    }
}
