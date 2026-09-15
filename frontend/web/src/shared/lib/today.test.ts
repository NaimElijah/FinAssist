// @vitest-environment node
// Pure date arithmetic: no DOM needed.

import { describe, expect, it } from 'vitest';

import { todayIsoDate } from './today';

describe('todayIsoDate', () => {
    it('Given_a_local_instant_after_midnight_When_read_Then_it_returns_that_local_calendar_date', () => {
        expect(todayIsoDate(new Date(2026, 8, 1, 0, 30))).toBe('2026-09-01');
    });

    it('Given_a_month_and_day_below_ten_When_read_Then_both_are_zero_padded', () => {
        expect(todayIsoDate(new Date(2026, 0, 5, 12, 0))).toBe('2026-01-05');
    });

    it('Given_the_last_day_of_the_year_When_read_Then_no_month_or_year_rolls_over', () => {
        expect(todayIsoDate(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31');
    });
});
