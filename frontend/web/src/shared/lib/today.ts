/**
 * Today's date in the browser's own calendar, for a lifecycle action's default date.
 *
 * Every screen that offers a date defaulting to "today" - recording a transaction, clearing,
 * reconciling or voiding one - reads the clock once, at the call site, and passes it to
 * `todayIsoDate` here, never inside it: a helper that called `new Date()` itself could not be
 * driven by a test, and no domain function calls `now()` (see `.claude/CLAUDE.md`).
 */

/**
 * `now`'s local calendar date, as `YYYY-MM-DD`.
 *
 * Built from `getFullYear`/`getMonth`/`getDate`, which read the local calendar, never from
 * `now.toISOString()`, which reads UTC and can name the previous or next day depending on the
 * browser's offset and the time of day.
 *
 * @param now - the current instant, typically `new Date()` at the call site.
 * @returns the local calendar date `now` falls on, such as `'2026-09-01'`.
 */
export function todayIsoDate(now: Date): string {
    const year = String(now.getFullYear()).padStart(4, '0');
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}
