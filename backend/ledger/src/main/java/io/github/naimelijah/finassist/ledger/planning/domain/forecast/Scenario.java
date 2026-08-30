package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A named strategy: the flows it assumes, and what it assumes investments will do.
 *
 * <p>This is the unit that gets compared. "Buy the car in March" and "keep contributing instead"
 * are two scenarios over the same horizon, and {@link Comparison} says what the difference comes to.
 *
 * <p>Every flow is in the scenario's currency. There are no exchange rates anywhere in FinAssist
 * (ADR-012), so a scenario mixing ILS and USD flows would produce a timeline whose numbers could not
 * legitimately be added — and adding them is the entire point of a timeline.
 *
 * <p><strong>A scenario with no flows at all is legal and useful.</strong> It is the do-nothing
 * baseline, which is what every comparison needs something to compare against; refusing it would
 * make the most important scenario the one you cannot express.
 */
public record Scenario(
        ScenarioId id, String name, Currency currency, List<PlannedFlow> flows, GrowthAssumption growth) {

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(flows, "flows");

        if (name.isBlank()) {
            throw new IllegalArgumentException("A scenario must have a name; it is how a comparison refers to it");
        }
        name = name.strip();

        flows = List.copyOf(flows);
        Set<PlanId> seen = new HashSet<>();
        for (PlannedFlow flow : flows) {
            if (flow.currency() != currency) {
                throw new IllegalArgumentException("Flow '" + flow.description() + "' is in " + flow.currency()
                        + " but the scenario is in " + currency);
            }
            // The same plan listed twice would be spent twice by every projection, with nothing in
            // the output to show for it. Two genuinely separate payments are two PlannedFlows with
            // two identities; one plan repeated is a mistake in assembling the list.
            if (!seen.add(flow.id())) {
                throw new IllegalArgumentException(
                        "Flow '" + flow.description() + "' appears twice in this scenario; it would be counted twice");
            }
        }
    }

    /** Builds a scenario, generating its identity. */
    public static Scenario of(String name, Currency currency, List<PlannedFlow> flows, GrowthAssumption growth) {
        return new Scenario(ScenarioId.generate(), name, currency, flows, growth);
    }

    /** The do-nothing baseline: no flows, and whatever the investments would do on their own. */
    public static Scenario baseline(String name, Currency currency, GrowthAssumption growth) {
        return of(name, currency, List.of(), growth);
    }

    /** Only the flows a projection should count; see {@link PlannedFlow#countsTowardsProjection}. */
    public List<PlannedFlow> forecastableFlows() {
        return flows.stream().filter(PlannedFlow::countsTowardsProjection).toList();
    }
}
