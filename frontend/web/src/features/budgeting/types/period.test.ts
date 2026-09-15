// @vitest-environment node
// Pure calendar arithmetic; no DOM needed. The label case is zone-dependent - it formats through
// Intl.DateTimeFormat - so it sets process.env.TZ and reloads the module fresh, mirroring
// DateText.test.tsx's pattern (Ruling 38).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    isSingleCalendarMonth,
    monthInputToPeriod,
    periodDisplayEnd,
    periodLabel,
    periodMonthLabel,
} from './period';

describe('monthInputToPeriod', () => {
    it('Given_a_month_input_When_mapped_Then_it_returns_the_first_of_the_month_to_the_first_of_the_next', () => {
        expect(monthInputToPeriod('2026-09')).toEqual({ start: '2026-09-01', end: '2026-10-01' });
    });

    it('Given_December_When_mapped_Then_the_end_rolls_into_next_January', () => {
        expect(monthInputToPeriod('2026-12')).toEqual({ start: '2026-12-01', end: '2027-01-01' });
    });

    it('Given_a_value_that_is_not_YYYY_MM_When_mapped_Then_it_throws', () => {
        expect(() => monthInputToPeriod('2026/09')).toThrow(/YYYY-MM/);
    });

    it('Given_month_00_When_mapped_Then_it_throws', () => {
        expect(() => monthInputToPeriod('2026-00')).toThrow(/YYYY-MM/);
    });

    it('Given_month_13_When_mapped_Then_it_throws', () => {
        expect(() => monthInputToPeriod('2026-13')).toThrow(/YYYY-MM/);
    });
});

describe('isSingleCalendarMonth', () => {
    it('Given_a_period_spanning_exactly_one_calendar_month_When_checked_Then_it_is_true', () => {
        expect(isSingleCalendarMonth({ start: '2026-09-01', end: '2026-10-01' })).toBe(true);
    });

    it('Given_December_rolling_into_January_When_checked_Then_it_is_still_a_single_month', () => {
        expect(isSingleCalendarMonth({ start: '2026-12-01', end: '2027-01-01' })).toBe(true);
    });

    it('Given_a_period_spanning_two_months_When_checked_Then_it_is_false', () => {
        expect(isSingleCalendarMonth({ start: '2026-09-01', end: '2026-11-01' })).toBe(false);
    });

    it('Given_a_period_not_starting_on_the_first_When_checked_Then_it_is_false', () => {
        expect(isSingleCalendarMonth({ start: '2026-09-15', end: '2026-10-15' })).toBe(false);
    });
});

describe('periodDisplayEnd', () => {
    it('Given_a_period_ending_mid_month_When_displayed_Then_it_shows_the_day_before_the_exclusive_end', () => {
        expect(periodDisplayEnd({ start: '2026-09-01', end: '2026-09-20' })).toBe('2026-09-19');
    });

    it('Given_a_period_whose_exclusive_end_is_the_first_of_a_month_When_displayed_Then_it_shows_the_last_day_of_the_previous_month', () => {
        expect(periodDisplayEnd({ start: '2026-09-01', end: '2026-10-01' })).toBe('2026-09-30');
    });

    it('Given_a_period_whose_exclusive_end_is_1_March_in_a_leap_year_When_displayed_Then_it_shows_29_February', () => {
        expect(periodDisplayEnd({ start: '2026-01-01', end: '2028-03-01' })).toBe('2028-02-29');
    });

    it('Given_a_period_whose_exclusive_end_is_1_January_When_displayed_Then_the_year_rolls_back_too', () => {
        expect(periodDisplayEnd({ start: '2025-06-01', end: '2026-01-01' })).toBe('2025-12-31');
    });
});

describe('periodMonthLabel', () => {
    let originalZone: string | undefined;

    beforeEach(() => {
        originalZone = process.env.TZ;
    });

    afterEach(() => {
        if (originalZone === undefined) {
            delete process.env.TZ;
        } else {
            process.env.TZ = originalZone;
        }
        vi.resetModules();
    });

    /** `periodMonthLabel` as evaluated after switching to `zone`. */
    async function periodMonthLabelEvaluatedIn(zone: string): Promise<typeof periodMonthLabel> {
        process.env.TZ = zone;
        vi.resetModules();
        const module = await import('./period');
        return module.periodMonthLabel;
    }

    it('Given_a_single_calendar_month_period_When_labelled_Then_it_reads_as_month_and_year', async () => {
        const label = await periodMonthLabelEvaluatedIn('UTC');

        expect(label({ start: '2026-09-01', end: '2026-10-01' })).toBe('September 2026');
    });

    it('Given_a_negative_offset_zone_When_labelled_Then_the_month_is_still_September', async () => {
        // The bug this guards against: Date.UTC(2026, 8, 1) formatted in the system zone rather
        // than pinned to UTC would read as 31 August in a zone west of Greenwich - the same
        // failure mode DateText.test.tsx's control case demonstrates for `new Date(isoString)`.
        const label = await periodMonthLabelEvaluatedIn('America/Los_Angeles');

        expect(label({ start: '2026-09-01', end: '2026-10-01' })).toBe('September 2026');
    });

    it('Given_a_period_spanning_two_months_When_labelled_Then_it_is_undefined', async () => {
        const label = await periodMonthLabelEvaluatedIn('UTC');

        expect(label({ start: '2026-09-01', end: '2026-11-01' })).toBeUndefined();
    });
});

describe('periodLabel', () => {
    it('Given_a_single_calendar_month_period_When_labelled_Then_it_is_the_month_label', () => {
        expect(periodLabel({ start: '2026-09-01', end: '2026-10-01' })).toBe('September 2026');
    });

    it('Given_a_period_that_is_not_a_single_calendar_month_When_labelled_Then_it_names_the_start_and_display_end', () => {
        expect(periodLabel({ start: '2026-09-01', end: '2026-09-20' })).toBe(
            '2026-09-01 to 2026-09-19',
        );
    });
});
