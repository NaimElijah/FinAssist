package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What must hold of <em>every</em> recurrence over <em>every</em> window, not just the calendar
 * corners {@code RecurrenceTest} picks out by hand.
 *
 * <p>Date arithmetic is where hand-chosen examples are weakest: the interesting inputs are month
 * ends, leap days and windows that open mid-period, and an author enumerating them will miss some.
 * These assert the invariants across generated schedules instead.
 *
 * <p>Generation is seeded, so the cases are identical on every run and a failure is reproducible.
 * See {@code MoneyPropertiesTest} and docs/ADRs/ADR-017-test-strategy.md.
 */
class RecurrencePropertiesTest {

    private static final long SEED = 20260830L;

    private static final int CASES = 100;

    private static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

    private static Stream<Arguments> schedules() {
        Random random = new Random(SEED);
        Cadence[] cadences = Cadence.values();
        return IntStream.range(0, CASES).mapToObj(index -> {
            LocalDate start = EPOCH.plusDays(random.nextInt(0, 900));
            LocalDate from = EPOCH.plusDays(random.nextInt(0, 900));
            LocalDate to = from.plusDays(random.nextInt(1, 800));
            Cadence cadence = cadences[random.nextInt(cadences.length)];
            int interval = random.nextInt(1, 4);
            Recurrence recurrence =
                    switch (random.nextInt(3)) {
                        case 0 -> new Recurrence.Once();
                        case 1 -> new Recurrence.Times(cadence, interval, random.nextInt(1, 12));
                        default -> new Recurrence.Until(cadence, interval, start.plusDays(random.nextInt(1, 900)));
                    };
            return Arguments.of(recurrence, start, from, to);
        });
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_any_schedule_When_occurrences_are_listed_Then_they_all_fall_inside_the_window(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        assertThat(recurrence.occurrences(start, from, to))
                .allSatisfy(date -> assertThat(date).isAfterOrEqualTo(from).isBefore(to));
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_any_schedule_When_occurrences_are_listed_Then_none_precedes_the_start(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        assertThat(recurrence.occurrences(start, from, to))
                .allSatisfy(date -> assertThat(date).isAfterOrEqualTo(start));
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_any_schedule_When_occurrences_are_listed_Then_they_ascend_without_repeating(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        List<LocalDate> occurrences = recurrence.occurrences(start, from, to);

        assertThat(occurrences).isSorted().doesNotHaveDuplicates();
    }

    /** A projection asks the same flow for the same window once per step; it must not drift. */
    @ParameterizedTest
    @MethodSource("schedules")
    void Given_any_schedule_When_asked_twice_Then_the_answer_is_the_same(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        assertThat(recurrence.occurrences(start, from, to)).isEqualTo(recurrence.occurrences(start, from, to));
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_a_counted_schedule_When_occurrences_are_listed_Then_there_are_never_more_than_the_count(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        if (recurrence instanceof Recurrence.Times times) {
            assertThat(recurrence.occurrences(start, from, to)).hasSizeLessThanOrEqualTo(times.count());
        }
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_a_bounded_schedule_When_occurrences_are_listed_Then_none_reaches_its_end_date(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        if (recurrence instanceof Recurrence.Until until) {
            assertThat(recurrence.occurrences(start, from, to))
                    .allSatisfy(date -> assertThat(date).isBefore(until.until()));
        }
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void Given_a_single_occurrence_schedule_When_listed_Then_it_yields_the_start_or_nothing(
            Recurrence recurrence, LocalDate start, LocalDate from, LocalDate to) {
        if (recurrence instanceof Recurrence.Once) {
            List<LocalDate> occurrences = recurrence.occurrences(start, from, to);
            boolean startIsInsideTheWindow = !start.isBefore(from) && start.isBefore(to);

            assertThat(occurrences).isEqualTo(startIsInsideTheWindow ? List.of(start) : List.of());
        }
    }
}
