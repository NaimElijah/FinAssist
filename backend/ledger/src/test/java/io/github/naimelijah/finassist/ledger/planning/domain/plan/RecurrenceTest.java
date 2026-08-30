package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Expanding a recurrence into dates, including the month-end case that catches everyone. */
class RecurrenceTest {

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate NEXT_JANUARY = LocalDate.of(2027, 1, 1);

    @Test
    void Given_a_one_off_within_the_range_When_expanded_Then_it_yields_its_own_date() {
        assertThat(new Recurrence.Once().occurrences(JANUARY, JANUARY, NEXT_JANUARY))
                .containsExactly(JANUARY);
    }

    @Test
    void Given_a_one_off_outside_the_range_When_expanded_Then_it_yields_nothing() {
        assertThat(new Recurrence.Once().occurrences(LocalDate.of(2025, 6, 1), JANUARY, NEXT_JANUARY))
                .isEmpty();
    }

    @Test
    void Given_a_monthly_recurrence_When_expanded_Then_it_yields_one_date_a_month() {
        Recurrence monthly = new Recurrence.Times(Cadence.MONTHLY, 1, 3);

        assertThat(monthly.occurrences(JANUARY, JANUARY, NEXT_JANUARY))
                .containsExactly(JANUARY, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
    }

    /**
     * The reason {@link Cadence#advance} measures from the start rather than the previous occurrence.
     * Advancing one month at a time would give 28 February and then 28 March, losing the 31st for
     * good; measuring from the start recovers it.
     */
    @Test
    void Given_a_recurrence_starting_on_the_31st_When_it_crosses_february_Then_it_clamps_and_recovers() {
        LocalDate endOfJanuary = LocalDate.of(2026, 1, 31);
        Recurrence monthly = new Recurrence.Times(Cadence.MONTHLY, 1, 3);

        assertThat(monthly.occurrences(endOfJanuary, endOfJanuary, NEXT_JANUARY))
                .containsExactly(endOfJanuary, LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
    }

    @Test
    void Given_an_every_other_month_recurrence_When_expanded_Then_it_skips_the_months_between() {
        Recurrence everyOther = new Recurrence.Times(Cadence.MONTHLY, 2, 3);

        assertThat(everyOther.occurrences(JANUARY, JANUARY, NEXT_JANUARY))
                .containsExactly(JANUARY, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1));
    }

    @Test
    void Given_a_recurrence_bounded_by_a_date_When_expanded_Then_that_date_is_excluded() {
        Recurrence until = new Recurrence.Until(Cadence.MONTHLY, 1, LocalDate.of(2026, 3, 1));

        assertThat(until.occurrences(JANUARY, JANUARY, NEXT_JANUARY))
                .containsExactly(JANUARY, LocalDate.of(2026, 2, 1));
    }

    @Test
    void Given_occurrences_before_the_range_When_expanded_Then_only_those_inside_are_yielded() {
        Recurrence weekly = new Recurrence.Times(Cadence.WEEKLY, 1, 4);
        LocalDate start = LocalDate.of(2025, 12, 11);

        assertThat(weekly.occurrences(start, JANUARY, NEXT_JANUARY)).containsExactly(LocalDate.of(2026, 1, 1));
    }

    @Test
    void Given_an_interval_below_one_When_a_recurrence_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Recurrence.Times(Cadence.MONTHLY, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void Given_a_count_below_one_When_a_recurrence_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Recurrence.Times(Cadence.MONTHLY, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least once");
    }

    @Test
    void Given_a_range_that_does_not_start_before_it_ends_When_expanded_Then_it_is_refused() {
        assertThatThrownBy(() -> new Recurrence.Once().occurrences(JANUARY, NEXT_JANUARY, JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
    }

    /**
     * Refused rather than answered with an empty list. {@code Recurrence} is public API that a
     * caller can reach without going through {@code PlannedFlow}, and a silently empty series would
     * surface as a projection that simply came out flat.
     */
    @Test
    void Given_a_recurrence_ending_before_it_starts_When_expanded_Then_it_is_refused() {
        Recurrence endsBeforeItBegins = new Recurrence.Until(Cadence.MONTHLY, 1, JANUARY);

        assertThatThrownBy(() -> endsBeforeItBegins.occurrences(LocalDate.of(2026, 3, 1), JANUARY, NEXT_JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never occur");
    }

    @Test
    void Given_a_yearly_recurrence_When_expanded_over_one_year_Then_it_yields_only_the_first() {
        Recurrence yearly = new Recurrence.Times(Cadence.YEARLY, 1, 5);

        assertThat(yearly.occurrences(JANUARY, JANUARY, NEXT_JANUARY)).containsExactly(JANUARY);
    }
}
