// @vitest-environment node
// Exercises the clock helper itself: process.env.TZ and Date are Node's, so no DOM is needed.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { pinClockJustAfterLocalMidnight } from './localClock';

describe('pinClockJustAfterLocalMidnight', () => {
    let restore: () => void = () => undefined;

    afterEach(() => {
        restore();
        restore = () => undefined;
    });

    it('Given_a_pinned_date_When_the_clock_is_read_locally_Then_it_names_that_date', () => {
        restore = pinClockJustAfterLocalMidnight('2026-09-15');

        const now = new Date();

        expect([now.getFullYear(), now.getMonth() + 1, now.getDate()]).toEqual([2026, 9, 15]);
    });

    it('Given_a_pinned_date_When_the_clock_is_read_as_UTC_Then_it_names_the_day_before', () => {
        // The control every screen test using the helper relies on: a default built through
        // toISOString() would disagree with the local date those tests expect.
        restore = pinClockJustAfterLocalMidnight('2026-09-15');

        expect(new Date().toISOString().slice(0, 10)).toBe('2026-09-14');
    });

    it('Given_a_pinned_clock_When_restored_Then_the_original_zone_and_real_timers_are_back', () => {
        const originalZone = process.env.TZ;
        pinClockJustAfterLocalMidnight('2026-09-15')();

        expect(process.env.TZ).toBe(originalZone);
        expect(vi.isFakeTimers()).toBe(false);
    });
});
