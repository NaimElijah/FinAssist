package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A projection is derived from a scenario; every figure here follows from the plans behind it. */
class ProjectionTest {

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate FEBRUARY = LocalDate.of(2026, 2, 1);
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private static final Horizon QUARTER = new Horizon(JANUARY, APRIL);

    private static Money ils(String amount) {
        return Money.of(amount, Currency.ILS);
    }

    private static PlannedFlow monthly(String amount, Direction direction, int times) {
        return PlannedFlow.plan(
                direction.name(), ils(amount), direction, JANUARY, new Recurrence.Times(Cadence.MONTHLY, 1, times));
    }

    @Test
    void Given_a_baseline_and_no_growth_When_projected_Then_every_point_holds_the_opening_balance() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        Projection projection =
                Projection.of(baseline, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.points()).extracting(BalancePoint::cash).containsOnly(ils("1000.00"));
        assertThat(projection.points()).extracting(BalancePoint::date).containsExactly(JANUARY, FEBRUARY, MARCH, APRIL);
    }

    @Test
    void Given_a_monthly_outflow_When_projected_Then_cash_falls_by_it_each_month() {
        Scenario scenario = Scenario.of(
                "Rent", Currency.ILS, List.of(monthly("100.00", Direction.OUTFLOW, 3)), GrowthAssumption.none());

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.points())
                .extracting(BalancePoint::cash)
                .containsExactly(ils("1000.00"), ils("900.00"), ils("800.00"), ils("700.00"));
    }

    @Test
    void Given_a_monthly_inflow_When_projected_Then_cash_rises_by_it_each_month() {
        Scenario scenario = Scenario.of(
                "Salary", Currency.ILS, List.of(monthly("2500.00", Direction.INFLOW, 3)), GrowthAssumption.none());

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, Money.zero(Currency.ILS), Money.zero(Currency.ILS));

        assertThat(projection.endState().cash()).isEqualTo(ils("7500.00"));
    }

    @Test
    void Given_growth_of_ten_percent_a_step_When_projected_Then_the_invested_balance_compounds() {
        Scenario scenario = Scenario.baseline("Hold", Currency.ILS, GrowthAssumption.perStep("0.10"));

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, Money.zero(Currency.ILS), ils("1000.00"));

        assertThat(projection.points())
                .extracting(BalancePoint::invested)
                .containsExactly(ils("1000.00"), ils("1100.00"), ils("1210.00"), ils("1331.00"));
    }

    @Test
    void Given_an_investment_flow_When_projected_Then_cash_falls_and_investments_rise() {
        PlannedFlow contribution = PlannedFlow.plan(
                "Monthly contribution", ils("200.00"), Direction.INVESTMENT, JANUARY, new Recurrence.Once());
        Scenario scenario = Scenario.of("Contribute", Currency.ILS, List.of(contribution), GrowthAssumption.none());

        Projection projection = Projection.of(
                scenario, new Horizon(JANUARY, FEBRUARY), Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.endState().cash()).isEqualTo(ils("800.00"));
        assertThat(projection.endState().invested()).isEqualTo(ils("200.00"));
        assertThat(projection.endState().total()).isEqualTo(ils("1000.00"));
    }

    @Test
    void Given_a_plan_that_outspends_the_balance_When_projected_Then_the_first_shortfall_is_reported() {
        Scenario scenario = Scenario.of(
                "Overspend", Currency.ILS, List.of(monthly("100.00", Direction.OUTFLOW, 3)), GrowthAssumption.none());

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, ils("150.00"), Money.zero(Currency.ILS));

        assertThat(projection.firstShortfall()).contains(MARCH);
    }

    @Test
    void Given_cash_that_never_goes_negative_When_projected_Then_no_shortfall_is_reported() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        Projection projection =
                Projection.of(baseline, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.firstShortfall()).isEmpty();
    }

    @Test
    void Given_a_realised_flow_When_projected_Then_it_does_not_move_the_balance_again() {
        PlannedFlow alreadyHappened =
                monthly("100.00", Direction.OUTFLOW, 3).commit(JANUARY).realise(JANUARY);
        Scenario scenario =
                Scenario.of("Already spent", Currency.ILS, List.of(alreadyHappened), GrowthAssumption.none());

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.endState().cash()).isEqualTo(ils("1000.00"));
    }

    @Test
    void Given_an_opening_balance_in_another_currency_When_projected_Then_it_is_refused() {
        Scenario scenario = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        assertThatThrownBy(() -> Projection.of(
                        scenario,
                        QUARTER,
                        Cadence.MONTHLY,
                        Money.of("1000.00", Currency.USD),
                        Money.zero(Currency.ILS)))
                .isInstanceOf(IllegalArgumentException.class)
                // Names the guard that refused it, not merely that something refused it: without
                // this, deleting the opening-cash check still failed downstream with the same
                // words, and mutation testing showed the assertion could not tell the difference.
                .hasMessageContaining("opening cash");
    }

    @Test
    void Given_a_horizon_that_is_not_a_whole_number_of_steps_When_projected_Then_it_is_refused() {
        Scenario scenario = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        Horizon ragged = new Horizon(JANUARY, LocalDate.of(2026, 3, 15));

        assertThatThrownBy(() ->
                        Projection.of(scenario, ragged, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a whole number");
    }

    @Test
    void Given_a_horizon_that_starts_after_it_ends_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Horizon(APRIL, JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
    }

    @Test
    void Given_a_negative_invested_opening_When_projected_Then_it_is_refused() {
        Scenario scenario = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        assertThatThrownBy(() -> Projection.of(scenario, QUARTER, Cadence.MONTHLY, ils("1000.00"), ils("-1.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void Given_points_that_do_not_advance_in_time_When_a_projection_is_built_Then_it_is_refused() {
        List<BalancePoint> repeated = List.of(
                new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(APRIL, ils("1.00"), Money.zero(Currency.ILS)));

        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, repeated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must advance in time");
    }

    @Test
    void Given_a_projection_that_does_not_close_on_the_horizon_When_built_Then_it_is_refused() {
        List<BalancePoint> tooShort = List.of(
                new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(FEBRUARY, ils("1.00"), Money.zero(Currency.ILS)));

        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must close on");
    }

    @Test
    void Given_a_balance_point_mixing_currencies_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("performs no conversion");
    }

    @Test
    void Given_a_total_across_two_currencies_When_taken_Then_Money_itself_refuses_it() {
        BalancePoint point = new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.ILS));

        assertThat(point.total()).isEqualTo(ils("1.00"));
        assertThatThrownBy(() -> ils("1.00").plus(Money.zero(Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void Given_points_off_the_step_boundaries_When_a_projection_is_built_Then_it_is_refused() {
        List<BalancePoint> drifted = List.of(
                new BalancePoint(JANUARY, ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(LocalDate.of(2026, 2, 15), ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(MARCH, ils("1.00"), Money.zero(Currency.ILS)),
                new BalancePoint(APRIL, ils("1.00"), Money.zero(Currency.ILS)));

        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundary there is 2026-02-01");
    }

    /**
     * The month-end case that a plain calendar count gets wrong: 31 January to 28 February is one
     * month by {@code Cadence.advance}, which clamps, and zero by {@code ChronoUnit.MONTHS}, which
     * counts whole periods. The horizon tiles, so it must be accepted.
     */
    @Test
    void Given_a_horizon_from_the_31st_to_a_clamped_month_end_When_projected_Then_it_tiles() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        Horizon endOfMonth = new Horizon(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));

        Projection projection =
                Projection.of(baseline, endOfMonth, Cadence.MONTHLY, ils("10.00"), Money.zero(Currency.ILS));

        assertThat(projection.points())
                .extracting(BalancePoint::date)
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));
    }

    @Test
    void Given_several_occurrences_inside_one_step_When_projected_Then_all_of_them_land() {
        PlannedFlow weekly = PlannedFlow.plan(
                "Weekly shop", ils("100.00"), Direction.OUTFLOW, JANUARY, new Recurrence.Times(Cadence.WEEKLY, 1, 4));
        Scenario scenario = Scenario.of("Shopping", Currency.ILS, List.of(weekly), GrowthAssumption.none());

        Projection projection = Projection.of(
                scenario, new Horizon(JANUARY, FEBRUARY), Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.endState().cash()).isEqualTo(ils("600.00"));
    }

    /**
     * A documented blind spot rather than an accepted behaviour: the series has no point between the
     * two flows, so the nineteen days of overdraft between them are invisible. Pinned by a test so
     * that narrowing it later is a deliberate change and not a surprise. See ADR-016.
     */
    @Test
    void Given_an_overdraft_opening_and_closing_inside_one_step_When_projected_Then_it_goes_unreported() {
        PlannedFlow bigPayment =
                PlannedFlow.plan("Tax bill", ils("5000.00"), Direction.OUTFLOW, JANUARY, new Recurrence.Once());
        PlannedFlow salary = PlannedFlow.plan(
                "Salary", ils("5000.00"), Direction.INFLOW, LocalDate.of(2026, 1, 20), new Recurrence.Once());
        Scenario scenario =
                Scenario.of("Tight month", Currency.ILS, List.of(bigPayment, salary), GrowthAssumption.none());

        Projection projection = Projection.of(
                scenario, new Horizon(JANUARY, FEBRUARY), Cadence.MONTHLY, ils("100.00"), Money.zero(Currency.ILS));

        assertThat(projection.firstShortfall()).isEmpty();
        assertThat(projection.endState().cash()).isEqualTo(ils("100.00"));
    }

    @Test
    void Given_a_weekly_step_When_projected_Then_a_point_falls_on_every_week() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());
        Horizon fourWeeks = new Horizon(JANUARY, JANUARY.plusWeeks(4));

        Projection projection =
                Projection.of(baseline, fourWeeks, Cadence.WEEKLY, ils("10.00"), Money.zero(Currency.ILS));

        assertThat(projection.points()).hasSize(5);
        assertThat(projection.endState().date()).isEqualTo(JANUARY.plusWeeks(4));
    }

    @Test
    void Given_opening_investments_in_another_currency_When_projected_Then_it_is_refused() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        assertThatThrownBy(() -> Projection.of(
                        baseline, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.of("500.00", Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opening investments");
    }

    @Test
    void Given_no_points_at_all_When_a_projection_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least its opening point");
    }

    @Test
    void Given_points_that_start_late_When_a_projection_is_built_Then_it_is_refused() {
        BalancePoint february = new BalancePoint(FEBRUARY, ils("1000.00"), Money.zero(Currency.ILS));
        BalancePoint april = new BalancePoint(APRIL, ils("1000.00"), Money.zero(Currency.ILS));

        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, List.of(february, april)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must open on");
    }

    @Test
    void Given_points_in_two_currencies_When_a_projection_is_built_Then_it_is_refused() {
        BalancePoint january = new BalancePoint(JANUARY, ils("1000.00"), Money.zero(Currency.ILS));
        BalancePoint february = new BalancePoint(FEBRUARY, Money.of("1000.00", Currency.USD), Money.zero(Currency.USD));
        BalancePoint march = new BalancePoint(MARCH, ils("1000.00"), Money.zero(Currency.ILS));
        BalancePoint april = new BalancePoint(APRIL, ils("1000.00"), Money.zero(Currency.ILS));

        assertThatThrownBy(() -> new Projection(QUARTER, Cadence.MONTHLY, List.of(january, february, march, april)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The bucketing loop stops advancing at {@code steps - 1}, so an occurrence in the final step
     * lands in the last bucket rather than running off the end. Mutation testing found the
     * boundary unpinned: moving it broke no test.
     */
    @Test
    void Given_a_flow_falling_in_the_final_step_When_projected_Then_it_lands_in_the_closing_point() {
        PlannedFlow inMarch =
                PlannedFlow.plan("Last month only", ils("100.00"), Direction.OUTFLOW, MARCH, new Recurrence.Once());
        Scenario scenario = Scenario.of("Late spend", Currency.ILS, List.of(inMarch), GrowthAssumption.none());

        Projection projection =
                Projection.of(scenario, QUARTER, Cadence.MONTHLY, ils("1000.00"), Money.zero(Currency.ILS));

        assertThat(projection.points())
                .extracting(BalancePoint::cash)
                .containsExactly(ils("1000.00"), ils("1000.00"), ils("1000.00"), ils("900.00"));
    }
}
