package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Two or more named projections, held side by side so their differences can be read off.
 *
 * <p>This is what makes "should I buy the car or keep contributing" a question with an answer: run
 * both as scenarios over the same horizon and see what the choice costs.
 *
 * <p><strong>Mismatched projections are refused, not reconciled.</strong> Different currencies have
 * no conversion between them (ADR-012); different horizons, step cadences or point dates have no
 * honest alignment. A comparison that quietly rebased one side would produce a confident number that
 * answers a question nobody asked, which is worse than no comparison at all.
 *
 * <p>A comparison reports differences and stops there. It does not rank scenarios or nominate a
 * winner — FinAssist is explicitly not a recommendation engine, and the arithmetic here has no
 * opinion about what the owner should want.
 *
 * <p><strong>Equality ignores the order the scenarios were given in.</strong> The constructor keeps
 * that order, because it is the order a reader will see them in, but two comparisons over the same
 * named projections are the same comparison whichever way round they were listed — presentation
 * order is not identity. The generated {@code equals} compares the underlying map, which is exactly
 * that behaviour.
 */
public record Comparison(Map<String, Projection> projections) {

    private static final int MINIMUM_SCENARIOS = 2;

    public Comparison {
        Objects.requireNonNull(projections, "projections");

        // Insertion order is the order the caller chose to present them in, and Map.copyOf does not
        // keep it. A comparison whose columns reshuffle between calls is a poor thing to read.
        Map<String, Projection> ordered = new LinkedHashMap<>();
        Projection first = null;
        for (Map.Entry<String, Projection> entry : projections.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "name");
            Projection projection = Objects.requireNonNull(entry.getValue(), "projection");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Every projection in a comparison must be named");
            }
            if (first == null) {
                first = projection;
            } else {
                requireComparable(first, projection, name);
            }
            // Names are stripped before use, so two keys the caller saw as distinct can collide
            // here. Refusing beats letting one silently replace the other: the caller asked to
            // compare two scenarios and would otherwise get a comparison holding one, reporting no
            // differences at all.
            if (ordered.put(name.strip(), projection) != null) {
                throw new IllegalArgumentException(
                        "Two scenarios in this comparison are both named '" + name.strip() + "'");
            }
        }

        // Counted after normalisation rather than before, so a map that collapsed to a single
        // scenario cannot satisfy a check made against the caller's own key count.
        if (ordered.size() < MINIMUM_SCENARIOS) {
            throw new IllegalArgumentException("A comparison needs at least " + MINIMUM_SCENARIOS
                    + " projections but was given " + ordered.size()
                    + "; compare a scenario against the do-nothing baseline");
        }
        projections = Collections.unmodifiableMap(ordered);
    }

    /** Builds a comparison from named projections, preserving the order given. */
    public static Comparison of(Map<String, Projection> projections) {
        return new Comparison(projections);
    }

    public Set<String> names() {
        return projections.keySet();
    }

    public Currency currency() {
        return any().currency();
    }

    public Horizon horizon() {
        return any().horizon();
    }

    public Cadence step() {
        return any().step();
    }

    /**
     * One projection by name.
     *
     * @throws IllegalArgumentException if no scenario of that name is in this comparison
     */
    public Projection projection(String name) {
        Projection projection =
                projections.get(Objects.requireNonNull(name, "name").strip());
        if (projection == null) {
            throw new IllegalArgumentException(
                    "No scenario named '" + name + "' in this comparison; it holds " + names());
        }
        return projection;
    }

    /** Where each scenario ends up, keyed by name and in the order given. */
    public Map<String, BalancePoint> endStates() {
        Map<String, BalancePoint> ends = new LinkedHashMap<>();
        projections.forEach((name, projection) -> ends.put(name, projection.endState()));
        return Collections.unmodifiableMap(ends);
    }

    /**
     * How far every other scenario drifts from {@code baseline}, step by step.
     *
     * <p>Each {@link Divergence} is that scenario minus the baseline, so a positive figure means the
     * scenario is ahead. The baseline itself is absent from the result: its divergence from itself
     * is zero at every step and reporting it would be noise.
     *
     * @throws IllegalArgumentException if {@code baseline} names no scenario here
     */
    public Map<String, List<Divergence>> divergenceFrom(String baseline) {
        Projection reference = projection(baseline);
        String referenceName = baseline.strip();

        Map<String, List<Divergence>> divergences = new LinkedHashMap<>();
        projections.forEach((name, projection) -> {
            if (name.equals(referenceName)) {
                return;
            }
            List<Divergence> series = new ArrayList<>();
            for (int index = 0; index < projection.points().size(); index++) {
                BalancePoint point = projection.points().get(index);
                BalancePoint against = reference.points().get(index);
                series.add(new Divergence(
                        point.date(),
                        point.cash().minus(against.cash()),
                        point.invested().minus(against.invested())));
            }
            divergences.put(name, List.copyOf(series));
        });
        return Collections.unmodifiableMap(divergences);
    }

    private Projection any() {
        return projections.values().iterator().next();
    }

    private static void requireComparable(Projection first, Projection other, String name) {
        if (other.currency() != first.currency()) {
            throw new IllegalArgumentException("Scenario '" + name + "' is in " + other.currency() + " but the"
                    + " comparison is in " + first.currency() + "; FinAssist performs no conversion");
        }
        if (!other.horizon().equals(first.horizon())) {
            throw new IllegalArgumentException("Scenario '" + name + "' covers " + other.horizon() + " but the"
                    + " comparison covers " + first.horizon() + "; there is no honest way to align them");
        }
        if (other.step() != first.step()) {
            throw new IllegalArgumentException(
                    "Scenario '" + name + "' steps " + other.step() + " but the comparison" + " steps " + first.step());
        }
        // Projection guarantees its points sit on the step boundaries of its horizon, so agreeing on
        // horizon and cadence should already mean agreeing on dates. Checked anyway, because
        // divergenceFrom subtracts these series index by index: a mismatch there would not throw, it
        // would quietly report the difference between two different dates.
        if (!datesOf(other).equals(datesOf(first))) {
            throw new IllegalArgumentException("Scenario '" + name + "' does not report the same dates as the"
                    + " comparison, so its points cannot be set against them");
        }
    }

    private static List<LocalDate> datesOf(Projection projection) {
        return projection.points().stream().map(BalancePoint::date).toList();
    }
}
