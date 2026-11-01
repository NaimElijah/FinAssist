package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Expanding a recurrence into dates, including the month-end case that catches everyone. */
class RecurrenceTest {

    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate NEXT_JANUARY = LocalDate.of(2027, 1, 1);

    /** A billion years: one step from 2026 passes {@link LocalDate#MAX}'s year of 999,999,999. */
    private static final int HUGE_INTERVAL = 1_000_000_000;

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

    /**
     * The expansion stops at the whole months between the start and the range's end, ten here; the
     * tenth step, 15 December, is the last that lands inside and must not be cut off.
     */
    @Test
    void Given_a_monthly_recurrence_whose_last_occurrence_is_the_final_whole_month_When_expanded_Then_it_is_kept() {
        Recurrence monthly = new Recurrence.Times(Cadence.MONTHLY, 1, 12);
        LocalDate midFebruary = LocalDate.of(2026, 2, 15);

        assertThat(monthly.occurrences(midFebruary, midFebruary, NEXT_JANUARY))
                .containsExactly(
                        midFebruary,
                        LocalDate.of(2026, 3, 15),
                        LocalDate.of(2026, 4, 15),
                        LocalDate.of(2026, 5, 15),
                        LocalDate.of(2026, 6, 15),
                        LocalDate.of(2026, 7, 15),
                        LocalDate.of(2026, 8, 15),
                        LocalDate.of(2026, 9, 15),
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2026, 11, 15),
                        LocalDate.of(2026, 12, 15));
    }

    /**
     * Two whole months separate 31 January from 1 April, and the second step lands on the bound
     * exactly: 31 March is inside the range and must be yielded.
     */
    @Test
    void Given_an_every_other_month_recurrence_from_the_31st_When_its_step_lands_on_the_bound_Then_it_is_kept() {
        Recurrence everyOther = new Recurrence.Times(Cadence.MONTHLY, 2, 3);
        LocalDate endOfJanuary = LocalDate.of(2026, 1, 31);

        assertThat(everyOther.occurrences(endOfJanuary, endOfJanuary, LocalDate.of(2026, 4, 1)))
                .containsExactly(endOfJanuary, LocalDate.of(2026, 3, 31));
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

    /**
     * An interval is bounded below only, so a caller can plan one so long that its second occurrence
     * lies past the last date {@link LocalDate} can hold. Only the first can fall in any range, and
     * computing the second must not be attempted, or the expansion throws rather than answers.
     */
    @Test
    void Given_a_bounded_recurrence_with_an_interval_beyond_the_calendar_When_expanded_Then_it_yields_only_the_first() {
        Recurrence until = new Recurrence.Until(Cadence.YEARLY, HUGE_INTERVAL, LocalDate.of(2030, 1, 1));

        assertThat(until.occurrences(JANUARY, JANUARY, NEXT_JANUARY)).containsExactly(JANUARY);
    }

    @Test
    void Given_a_counted_recurrence_with_an_interval_beyond_the_calendar_When_expanded_Then_it_yields_only_the_first() {
        Recurrence times = new Recurrence.Times(Cadence.YEARLY, HUGE_INTERVAL, 3);

        assertThat(times.occurrences(JANUARY, JANUARY, NEXT_JANUARY)).containsExactly(JANUARY);
    }

    /**
     * The guard lives in each implementation, so proving it for {@code Once} proves nothing about
     * the other two. Mutation testing found exactly that: deleting {@code requireRange} from
     * {@code Until} and {@code Times} broke no test.
     */
    @Test
    void Given_a_bounded_recurrence_and_an_inverted_range_When_expanded_Then_it_is_refused() {
        Recurrence until = new Recurrence.Until(Cadence.MONTHLY, 1, NEXT_JANUARY);

        assertThatThrownBy(() -> until.occurrences(JANUARY, NEXT_JANUARY, JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
    }

    @Test
    void Given_a_counted_recurrence_and_an_inverted_range_When_expanded_Then_it_is_refused() {
        Recurrence times = new Recurrence.Times(Cadence.MONTHLY, 1, 3);

        assertThatThrownBy(() -> times.occurrences(JANUARY, NEXT_JANUARY, JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start before it ends");
    }
}
