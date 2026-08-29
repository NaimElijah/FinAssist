package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A timeline of projected balances: what the owner is expected to hold at each step of a horizon.
 *
 * <p><strong>Derived, never stored.</strong> A projection is computed from an opening balance and a
 * {@link Scenario} by {@link #of}, exactly as an account balance is computed from its entries. There
 * is no way to set a point to a figure of your choosing and have the rest agree with it, which is
 * what stops a forecast from quietly disagreeing with the plans behind it.
 *
 * <p>The points run from {@link Horizon#from()} to {@link Horizon#to()} <em>inclusive</em>. The
 * horizon itself stays half-open, and that is not a contradiction: the half-open span decides which
 * dates a flow can fall on, while the points report the state at each boundary of that span,
 * including the one that closes it. Without the closing point the end state — the figure most
 * comparisons actually turn on — would be the one thing a projection never showed.
 *
 * <p>The first point is the opening state, before any growth or flow. Every later point is the state
 * at the end of its step: growth is applied to the invested balance opening the step, then the flows
 * falling in that step land. A flow dated on the first day of a step therefore shows in the point
 * that closes it, not the one that opens it.
 *
 * <p>Everything is in one currency (ADR-012). An owner with accounts in two currencies gets two
 * projections and no combined figure, because no rate exists with which to make one.
 */
public record Projection(Horizon horizon, Cadence step, List<BalancePoint> points) {

    public Projection {
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(points, "points");

        points = List.copyOf(points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("A projection needs at least its opening point");
        }
        if (!points.getFirst().date().equals(horizon.from())) {
            throw new IllegalArgumentException("A projection must open on " + horizon.from() + " but opens on "
                    + points.getFirst().date());
        }
        if (!points.getLast().date().equals(horizon.to())) {
            throw new IllegalArgumentException("A projection must close on " + horizon.to() + " but closes on "
                    + points.getLast().date());
        }

        Currency currency = points.getFirst().currency();
        LocalDate previous = null;
        for (BalancePoint point : points) {
            if (previous != null && !point.date().isAfter(previous)) {
                throw new IllegalArgumentException(
                        "Projection points must advance in time, but " + point.date() + " does not follow " + previous);
            }
            if (point.currency() != currency) {
                throw new IllegalArgumentException("A projection is in one currency, but " + point.date() + " is in "
                        + point.currency() + " where it opened in " + currency);
            }
            previous = point.date();
        }
    }

    /**
     * Projects {@code scenario} forward from an opening position.
     *
     * <p>The horizon must be a whole number of {@code step}s. A horizon that does not tile would
     * leave a final part-step whose flows belong to no point, and dropping them silently is the kind
     * of quiet wrongness a forecast cannot recover from; refusing is the honest alternative.
     *
     * @throws IllegalArgumentException if any amount is in a currency other than the scenario's, or
     *     if the horizon is not a whole number of steps
     */
    public static Projection of(
            Scenario scenario, Horizon horizon, Cadence step, Money openingCash, Money openingInvested) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(openingCash, "openingCash");
        Objects.requireNonNull(openingInvested, "openingInvested");

        requireScenarioCurrency(openingCash, scenario.currency(), "opening cash");
        requireScenarioCurrency(openingInvested, scenario.currency(), "opening investments");

        long steps = stepsAcross(horizon, step);
        List<PlannedFlow> flows = scenario.forecastableFlows();

        List<BalancePoint> points = new ArrayList<>();
        Money cash = openingCash;
        Money invested = openingInvested;
        points.add(new BalancePoint(horizon.from(), cash, invested));

        for (long period = 1; period <= steps; period++) {
            LocalDate opensOn = step.advance(horizon.from(), period - 1);
            LocalDate closesOn = step.advance(horizon.from(), period);

            invested = scenario.growth().applyTo(invested);

            for (PlannedFlow flow : flows) {
                int occurrences = flow.occurrences(opensOn, closesOn).size();
                for (int occurrence = 0; occurrence < occurrences; occurrence++) {
                    switch (flow.direction()) {
                        case INFLOW -> cash = cash.plus(flow.amount());
                        case OUTFLOW -> cash = cash.minus(flow.amount());
                        case INVESTMENT -> {
                            cash = cash.minus(flow.amount());
                            invested = invested.plus(flow.amount());
                        }
                    }
                }
            }
            points.add(new BalancePoint(closesOn, cash, invested));
        }
        return new Projection(horizon, step, points);
    }

    public Currency currency() {
        return points.getFirst().currency();
    }

    /** Where the projection ends up — the figure a comparison usually turns on. */
    public BalancePoint endState() {
        return points.getLast();
    }

    /**
     * The first date the projected cash balance is negative, if it ever is.
     *
     * <p>The most actionable thing a forecast produces: not "you will be fine" but "this plan runs
     * you dry in March". Empty when cash stays non-negative throughout.
     */
    public Optional<LocalDate> firstShortfall() {
        return points.stream()
                .filter(point -> point.cash().isNegative())
                .map(BalancePoint::date)
                .findFirst();
    }

    /** The point on an exact step boundary, or empty when the date is not one. */
    public Optional<BalancePoint> pointOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return points.stream().filter(point -> point.date().equals(date)).findFirst();
    }

    private static void requireScenarioCurrency(Money amount, Currency currency, String what) {
        if (amount.currency() != currency) {
            throw new IllegalArgumentException("The " + what + " is in " + amount.currency()
                    + " but the scenario is in " + currency + "; FinAssist performs no conversion");
        }
    }

    private static long stepsAcross(Horizon horizon, Cadence step) {
        for (long period = 1; ; period++) {
            LocalDate boundary = step.advance(horizon.from(), period);
            if (boundary.equals(horizon.to())) {
                return period;
            }
            if (boundary.isAfter(horizon.to())) {
                throw new IllegalArgumentException("A horizon of " + horizon.from() + " to " + horizon.to()
                        + " is not a whole number of " + step + " steps; the last part-step would hold flows that no"
                        + " point reports");
            }
        }
    }
}
