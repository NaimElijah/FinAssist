package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * A horizon spans at most {@value Horizon#MAXIMUM_SPAN_YEARS} years, because every step of it is computed
 * and held in memory, and is refused as a rule before anything is stepped across.
 */
class HorizonTest {

    private static final LocalDate JANUARY_2026 = LocalDate.of(2026, 1, 1);
    private static final LocalDate LEAP_DAY_2000 = LocalDate.of(2000, 2, 29);

    @Test
    void Given_a_span_of_exactly_the_maximum_When_built_Then_it_is_accepted() {
        LocalDate end = JANUARY_2026.plusYears(Horizon.MAXIMUM_SPAN_YEARS);

        Horizon horizon = new Horizon(JANUARY_2026, end);

        assertThat(horizon.to()).isEqualTo(end);
    }

    /**
     * 2000 is a leap year and 2100 is not, so a century from 29 February 2000 is clamped to 28 February
     * 2100: the last day the maximum span reaches from a leap day.
     */
    @Test
    void Given_a_span_from_a_leap_day_to_the_clamped_date_a_century_on_When_built_Then_it_is_accepted() {
        LocalDate clamped = LocalDate.of(2100, 2, 28);

        Horizon horizon = new Horizon(LEAP_DAY_2000, clamped);

        assertThat(horizon.to()).isEqualTo(clamped);
    }

    @Test
    void Given_a_span_from_a_leap_day_to_the_day_after_the_clamped_date_When_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Horizon(LEAP_DAY_2000, LocalDate.of(2100, 3, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + Horizon.MAXIMUM_SPAN_YEARS + " years");
    }

    @Test
    void Given_a_span_one_day_beyond_the_maximum_When_built_Then_it_is_refused() {
        LocalDate oneDayTooFar =
                JANUARY_2026.plusYears(Horizon.MAXIMUM_SPAN_YEARS).plusDays(1);

        assertThatThrownBy(() -> new Horizon(JANUARY_2026, oneDayTooFar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + Horizon.MAXIMUM_SPAN_YEARS + " years");
    }

    /** The widest dates a {@code LocalDate} holds: the check itself must not overflow on them. */
    @Test
    void Given_a_span_across_every_representable_date_When_built_Then_it_is_refused_as_a_rule() {
        assertThatThrownBy(() -> new Horizon(LocalDate.MIN, LocalDate.MAX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most " + Horizon.MAXIMUM_SPAN_YEARS + " years");
    }

    @Test
    void Given_a_short_span_ending_on_the_last_representable_date_When_built_Then_it_is_accepted() {
        LocalDate lastYear = LocalDate.MAX.minusYears(1);

        Horizon horizon = new Horizon(lastYear, LocalDate.MAX);

        assertThat(horizon.to()).isEqualTo(LocalDate.MAX);
    }

    /** The finest cadence over the widest horizon: the bound leaves every legal projection computable. */
    @Test
    void Given_the_maximum_span_When_projected_weekly_Then_every_step_is_a_point() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        long weeks = ChronoUnit.WEEKS.between(monday, monday.plusYears(Horizon.MAXIMUM_SPAN_YEARS));
        LocalDate end = monday.plusWeeks(weeks);
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        Projection projection = Projection.of(
                baseline,
                new Horizon(monday, end),
                Cadence.WEEKLY,
                Money.of("1000.00", Currency.ILS),
                Money.zero(Currency.ILS));

        assertThat(projection.points()).hasSize(Math.toIntExact(weeks) + 1);
        assertThat(projection.endState().date()).isEqualTo(end);
    }
}
