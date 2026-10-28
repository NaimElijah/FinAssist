// @vitest-environment node
// Exercises the clock helper itself: process.env.TZ and Date are Node's, so no DOM is needed.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { pinClockJustAfterLocalMidnight } from './localClock';

/** The local offset, in minutes, on the date every test here pins: UTC+14 reads -840. */
function offsetOnThePinnedDate(): number {
    return new Date(2026, 8, 15, 0, 30).getTimezoneOffset();
}

/**
 * The machine's own offset, read before any test here pins the clock. A restore that leaked the
 * pinned zone into a later test would otherwise move that test's own baseline along with it.
 */
const OFFSET_BEFORE_ANY_PIN = offsetOnThePinnedDate();

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
        // Checks the zone Node computes dates in, not only the variable: on Windows, deleting
        // process.env.TZ after setting it leaves Node in the zone last set.
        const originalZone = process.env.TZ;
        pinClockJustAfterLocalMidnight('2026-09-15')();

        expect(process.env.TZ).toBe(originalZone);
        expect(offsetOnThePinnedDate(), 'the local offset after restoring').toBe(
            OFFSET_BEFORE_ANY_PIN,
        );
        expect(vi.isFakeTimers()).toBe(false);
    });
});
