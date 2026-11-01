/**
 * The one place a calendar date, `YYYY-MM-DD`, becomes text on screen. It lives in `shared/lib`
 * rather than the UI kit so a feature's `types/`, which may not import the kit, formats a date
 * exactly as `DateText` renders it instead of keeping a copy that drifts.
 */
import { DISPLAY_LOCALE } from './locale';

/** A calendar date as the wire carries it: year, month and day, with no time and no zone. */
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** The only zone a formatter given to `formatCalendarDate` may name. */
const UTC = 'UTC';

/**
 * Formats in UTC because the instant below is built in UTC. Applying no offset at either end is
 * what keeps the day shown equal to the day written, in every zone the browser might be in.
 */
const DATE_FORMAT = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
    dateStyle: 'medium',
    timeZone: UTC,
});

/**
 * A calendar date as `DateText` shows it, for text that cannot hold an element, such as a
 * control's accessible name naming its row.
 *
 * The string is split into its year, month and day rather than given to `new Date(value)`, which
 * reads a bare date as UTC midnight: formatted in local time, that is the previous day anywhere
 * west of Greenwich.
 *
 * @param value - an ISO `YYYY-MM-DD` date, such as `"2026-09-01"`.
 * @param format - the formatter to apply, when the date is wanted in another shape than
 *   `DateText`'s, such as a month and year. It must be built with `timeZone: 'UTC'`.
 * @returns the date as `format` writes it; by default in `DISPLAY_LOCALE`'s medium style, such as
 *   "1 Sept 2026".
 * @throws Error - when `format` is not pinned to UTC, or `value` is not `YYYY-MM-DD` or names a
 *   day that does not exist, such as 30 February. The value is left out of the message, because a
 *   render error's message can reach the logs.
 */
export function formatCalendarDate(
    value: string,
    format: Intl.DateTimeFormat = DATE_FORMAT,
): string {
    if (format.resolvedOptions().timeZone !== UTC) {
        throw new Error('formatCalendarDate needs a formatter pinned to timeZone UTC');
    }
    const match = ISO_DATE.exec(value);
    if (match === null) {
        throw new Error('formatCalendarDate expects an ISO date, YYYY-MM-DD');
    }
    const year = Number(match[1]);
    const monthIndex = Number(match[2]) - 1;
    const day = Number(match[3]);

    // The year is set on its own, never through `Date.UTC`, which reads a year from 0 to 99 as
    // 1900 to 1999: the ledger accepts every year from 1, and a date input yields 0026 from a
    // typed "26".
    const instant = new Date(0);
    instant.setUTCFullYear(year, monthIndex, day);
    // setUTCFullYear rolls an impossible day into the next month instead of failing; a round trip
    // that changes any part means the input named no real day.
    if (
        instant.getUTCFullYear() !== year ||
        instant.getUTCMonth() !== monthIndex ||
        instant.getUTCDate() !== day
    ) {
        throw new Error('formatCalendarDate was given a value that is not a calendar date');
    }
    return format.format(instant);
}
