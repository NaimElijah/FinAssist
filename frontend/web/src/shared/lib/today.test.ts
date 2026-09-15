// @vitest-environment node
// Pure date arithmetic, but zone-dependent: each case sets `process.env.TZ`, which Node re-reads
// on assignment, then reloads the module fresh, mirroring DateText.test.tsx's pattern - so a
// case cannot pass merely because an earlier one in this run left a stale value cached.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/** `todayIsoDate` as evaluated after switching to `zone`. */
async function todayIsoDateEvaluatedIn(zone: string): Promise<(now: Date) => string> {
    process.env.TZ = zone;
    vi.resetModules();
    const module = await import('./today');
    return module.todayIsoDate;
}

describe('todayIsoDate', () => {
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

    it('Given_a_local_instant_after_midnight_When_read_Then_it_returns_that_local_calendar_date', async () => {
        const todayIsoDate = await todayIsoDateEvaluatedIn('UTC');

        expect(todayIsoDate(new Date(2026, 8, 1, 0, 30))).toBe('2026-09-01');
    });

    it('Given_a_month_and_day_below_ten_When_read_Then_both_are_zero_padded', async () => {
        const todayIsoDate = await todayIsoDateEvaluatedIn('UTC');

        expect(todayIsoDate(new Date(2026, 0, 5, 12, 0))).toBe('2026-01-05');
    });

    it('Given_the_last_day_of_the_year_When_read_Then_no_month_or_year_rolls_over', async () => {
        const todayIsoDate = await todayIsoDateEvaluatedIn('UTC');

        expect(todayIsoDate(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31');
    });

    it('Given_a_zone_far_enough_ahead_of_UTC_that_toISOString_would_name_the_previous_day_When_read_Then_the_local_day_is_still_returned', async () => {
        // Pacific/Kiritimati is UTC+14, the furthest-ahead IANA zone. Control: this instant's own
        // toISOString names 31 August, proving a toISOString-based implementation would fail the
        // assertion below - the exact bug todayIsoDate exists to avoid.
        process.env.TZ = 'Pacific/Kiritimati';
        const localMidnightish = new Date(2026, 8, 1, 0, 30);
        expect(localMidnightish.toISOString().slice(0, 10)).toBe('2026-08-31');

        const todayIsoDate = await todayIsoDateEvaluatedIn('Pacific/Kiritimati');

        expect(todayIsoDate(new Date(2026, 8, 1, 0, 30))).toBe('2026-09-01');
    });
});
