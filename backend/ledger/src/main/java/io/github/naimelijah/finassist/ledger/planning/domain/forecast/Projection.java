package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.math.BigDecimal;
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
 * <p>The points are <strong>exactly the step boundaries</strong> of the horizon, from
 * {@link Horizon#from()} to {@link Horizon#to()} <em>inclusive</em>. The horizon itself stays
 * half-open, and that is not a contradiction: the half-open span decides which dates a flow can fall
 * on, while the points report the state at each boundary of that span, including the one that closes
 * it. Without the closing point the end state — the figure most comparisons actually turn on — would
 * be the one thing a projection never showed.
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

    /**
     * Holds a series of points that already sits on the horizon's step boundaries.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if there are no points, the first is not dated
     *     {@code horizon.from()} or the last {@code horizon.to()}, a point is not dated on its
     *     step boundary, or the points are not all in one currency
     */
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
        for (int index = 0; index < points.size(); index++) {
            BalancePoint point = points.get(index);
            if (previous != null && !point.date().isAfter(previous)) {
                throw new IllegalArgumentException(
                        "Projection points must advance in time, but " + point.date() + " does not follow " + previous);
            }
            // Every point sits on a step boundary, so a projection's dates follow from its horizon
            // and cadence alone. Comparison relies on exactly that: two projections agreeing on
            // horizon and cadence therefore agree on dates, which is what lets it subtract them
            // point by point. Without this, a hand-built series could pass every other check here
            // and still be compared date-against-mismatched-date.
            LocalDate expected = step.advance(horizon.from(), index);
            if (!point.date().equals(expected)) {
                throw new IllegalArgumentException("Projection point " + index + " is dated " + point.date()
                        + " but the " + step + " boundary there is " + expected);
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

        int steps = Math.toIntExact(stepsAcross(horizon, step));
        List<LocalDate> boundaries = new ArrayList<>(steps + 1);
        for (int period = 0; period <= steps; period++) {
            boundaries.add(step.advance(horizon.from(), period));
        }

        Money[] cashDelta = new Money[steps];
        Money[] investedDelta = new Money[steps];
        for (int index = 0; index < steps; index++) {
            cashDelta[index] = Money.zero(scenario.currency());
            investedDelta[index] = Money.zero(scenario.currency());
        }

        for (PlannedFlow flow : scenario.forecastableFlows()) {
            // Each recurrence is expanded once, across the whole horizon, and its dates are then
            // bucketed into the step they fall in. Expanding per step instead made this quadratic:
            // every step re-walked every recurrence from its first occurrence, so a thirty-year
            // monthly plan spent most of its time recomputing dates it had already produced.
            long[] occurrencesPerStep = new long[steps];
            int index = 0;
            for (LocalDate date : flow.occurrences(horizon.from(), horizon.to())) {
                while (index < steps - 1 && !date.isBefore(boundaries.get(index + 1))) {
                    index++;
                }
                occurrencesPerStep[index]++;
            }

            for (int period = 0; period < steps; period++) {
                if (occurrencesPerStep[period] == 0) {
                    continue;
                }
                Money moved = flow.amount().times(BigDecimal.valueOf(occurrencesPerStep[period]));
                switch (flow.direction()) {
                    case INFLOW -> cashDelta[period] = cashDelta[period].plus(moved);
                    case OUTFLOW -> cashDelta[period] = cashDelta[period].minus(moved);
                    case INVESTMENT -> {
                        cashDelta[period] = cashDelta[period].minus(moved);
                        investedDelta[period] = investedDelta[period].plus(moved);
                    }
                }
            }
        }

        List<BalancePoint> points = new ArrayList<>(steps + 1);
        Money cash = openingCash;
        Money invested = openingInvested;
        points.add(new BalancePoint(horizon.from(), cash, invested));

        for (int period = 0; period < steps; period++) {
            invested = scenario.growth().applyTo(invested);
            cash = cash.plus(cashDelta[period]);
            invested = invested.plus(investedDelta[period]);
            points.add(new BalancePoint(boundaries.get(period + 1), cash, invested));
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
     * The first step boundary at which the projected cash balance is negative, if there is one.
     *
     * <p>The most actionable thing a forecast produces: not "you will be fine" but "this plan runs
     * you dry in March".
     *
     * <p><strong>It sees only the step boundaries, which is a real blind spot.</strong> A balance
     * that dips below zero after one flow and recovers after another <em>within the same step</em>
     * is never reported, because no point of the series stands between them — a large payment on the
     * 1st covered by a salary on the 20th looks untroubled at a monthly cadence, while the account
     * is overdrawn for nineteen days. Projecting at a finer {@link Cadence} narrows the window;
     * nothing here closes it. See ADR-016.
     */
    public Optional<LocalDate> firstShortfall() {
        return points.stream()
                .filter(point -> point.cash().isNegative())
                .map(BalancePoint::date)
                .findFirst();
    }

    private static void requireScenarioCurrency(Money amount, Currency currency, String what) {
        if (amount.currency() != currency) {
            throw new IllegalArgumentException("The " + what + " is in " + amount.currency()
                    + " but the scenario is in " + currency + "; FinAssist performs no conversion");
        }
    }

    /**
     * How many whole steps span the horizon.
     *
     * <p>{@link Cadence#between} is only a starting point, because it counts <em>complete</em>
     * periods: from 31 January to 28 February is one month by {@link Cadence#advance} and zero
     * months by the calendar, since the end day is earlier than the start day. Both candidates are
     * therefore checked against {@code advance}, which is the definition the projection actually
     * steps by.
     */
    private static long stepsAcross(Horizon horizon, Cadence step) {
        long estimate = step.between(horizon.from(), horizon.to());
        for (long candidate = Math.max(estimate, 1); candidate <= estimate + 1; candidate++) {
            if (step.advance(horizon.from(), candidate).equals(horizon.to())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("A horizon of " + horizon.from() + " to " + horizon.to()
                + " is not a whole number of " + step + " steps; the last part-step would hold flows that no"
                + " point reports");
    }
}
