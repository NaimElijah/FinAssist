/**
 * A pinned clock for a screen test asserting a date that defaults to the browser's local today
 * (frontend/web CLAUDE.md: never `toISOString()`, which reads UTC).
 *
 * The clock is pinned half an hour after local midnight in the zone furthest ahead of UTC, where
 * the UTC calendar date is still the day before. A test that pins noon in whatever zone the
 * machine runs in cannot fail when a call site reads UTC instead; this one does.
 */
import { vi } from 'vitest';

/** UTC+14, the furthest-ahead IANA zone: 00:30 local here is 10:30 the previous day in UTC. */
const FAR_AHEAD_ZONE = 'Pacific/Kiritimati';

/**
 * Switch the process to `FAR_AHEAD_ZONE` and pin `Date` at 00:30 local on `isoDate`.
 *
 * Only `Date` is faked, so user-event's own delays still run on real timers. Node re-reads
 * `process.env.TZ` on assignment, so the switch takes effect for every `Date` built afterwards.
 *
 * @param isoDate - the local calendar date to pin, as `YYYY-MM-DD`.
 * @returns restores real timers and the zone the process had before; call it from `afterEach`.
 */
export function pinClockJustAfterLocalMidnight(isoDate: string): () => void {
    const originalZone = process.env.TZ;
    const resolvedZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    process.env.TZ = FAR_AHEAD_ZONE;
    const [year = NaN, month = NaN, day = NaN] = isoDate.split('-').map(Number);
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(year, month - 1, day, 0, 30));

    return () => {
        vi.useRealTimers();
        // Deleting TZ alone does not restore the zone on Windows: Node keeps the one last set.
        // Setting the zone resolved before pinning first puts it back on every platform.
        process.env.TZ = originalZone ?? resolvedZone;
        if (originalZone === undefined) delete process.env.TZ;
    };
}
