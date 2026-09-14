package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Divergence;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Scenarios side by side over one horizon, one step and one currency, each measured against the baseline.
 * Every member is always present, and marked required in the contract.
 *
 * <p>{@code scenarios} is an array in the order the request named them. The domain holds the comparison as
 * an ordered map keyed by name, and RFC 8259 leaves an object's member order unspecified, so a map whose
 * order matters cannot be an object (ADR-028). Each entry carries its id as well as its name, because a
 * name is unique within a comparison and not across the store.
 */
public record ComparisonResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HorizonResponse horizon,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence step,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID baseline,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ComparedScenarioResponse> scenarios) {

    /**
     * Maps a comparison back from names to the ids the request used.
     *
     * @param order the compared scenarios, in the order the request named them
     * @param baseline one of {@code order}
     * @param names the name of every scenario in {@code order}, which is how {@code comparison} keys them
     * @throws IllegalStateException if a compared scenario has no name, which cannot happen while nothing is
     *     removable: the comparison could not have found it otherwise
     */
    static ComparisonResponse from(
            Comparison comparison, List<ScenarioId> order, ScenarioId baseline, Map<ScenarioId, String> names) {
        Map<String, List<Divergence>> divergences = comparison.divergenceFrom(nameOf(baseline, names));
        List<ComparedScenarioResponse> scenarios = order.stream()
                .map(id -> {
                    String name = nameOf(id, names);
                    List<Divergence> divergence =
                            id.equals(baseline) ? List.of() : Objects.requireNonNull(divergences.get(name), name);
                    return ComparedScenarioResponse.from(id, name, comparison.projection(name), divergence);
                })
                .toList();
        return new ComparisonResponse(
                HorizonResponse.from(comparison.horizon()),
                comparison.step(),
                comparison.currency(),
                baseline.value(),
                scenarios);
    }

    private static String nameOf(ScenarioId id, Map<ScenarioId, String> names) {
        String name = names.get(id);
        if (name == null) {
            throw new IllegalStateException(
                    "Scenario " + id + " was compared but is not listed; scenarios are never removed");
        }
        return name;
    }
}
