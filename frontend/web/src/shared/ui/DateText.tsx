import { formatCalendarDate } from './calendarDate';
import styles from './DateText.module.css';

/** A calendar date to display. */
export interface DateTextProps {
    /** An ISO `YYYY-MM-DD` date, such as `"2026-09-01"`. */
    readonly value: string;
}

/**
 * Display a calendar date in `DISPLAY_LOCALE`, rendered as `<time dateTime={value}>`, formatted by
 * `formatCalendarDate`.
 *
 * @param props - see `DateTextProps`.
 * @throws Error - as `formatCalendarDate` does: when `value` is not `YYYY-MM-DD`, or names a day
 *   that does not exist.
 */
export function DateText({ value }: DateTextProps) {
    return (
        <time dateTime={value} className={styles.date}>
            {formatCalendarDate(value)}
        </time>
    );
}
