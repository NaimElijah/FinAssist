import styles from './DateText.module.css';
import { DISPLAY_LOCALE } from '../lib/locale';

/** A calendar date as the wire carries it: year, month and day, with no time and no zone. */
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Formats in UTC because the instant below is built in UTC. Applying no offset at either end is
 * what keeps the day shown equal to the day written, in every zone the browser might be in.
 */
const DATE_FORMAT = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
    dateStyle: 'medium',
    timeZone: 'UTC',
});

/** A calendar date to display. */
export interface DateTextProps {
    /** An ISO `YYYY-MM-DD` date, such as `"2026-09-01"`. */
    readonly value: string;
}

/**
 * Display a calendar date in `DISPLAY_LOCALE`, rendered as `<time dateTime={value}>`.
 *
 * The string is split into its year, month and day rather than given to `new Date(value)`, which
 * reads a bare date as UTC midnight: formatted in local time, that is the previous day anywhere
 * west of Greenwich.
 *
 * @param props - see `DateTextProps`.
 * @throws Error - when `value` is not `YYYY-MM-DD`, or names a day that does not exist, such as
 *   30 February. The value is left out of the message, because a render error's message can
 *   reach the logs.
 */
export function DateText({ value }: DateTextProps) {
    const match = ISO_DATE.exec(value);
    if (match === null) {
        throw new Error('DateText expects an ISO date, YYYY-MM-DD');
    }
    const year = Number(match[1]);
    const monthIndex = Number(match[2]) - 1;
    const day = Number(match[3]);

    const instant = new Date(Date.UTC(year, monthIndex, day));
    // Date.UTC rolls an impossible day into the next month instead of failing; a round trip that
    // changes any part means the input named no real day.
    if (
        instant.getUTCFullYear() !== year ||
        instant.getUTCMonth() !== monthIndex ||
        instant.getUTCDate() !== day
    ) {
        throw new Error('DateText was given a value that is not a calendar date');
    }

    return (
        <time dateTime={value} className={styles.date}>
            {DATE_FORMAT.format(instant)}
        </time>
    );
}
