/**
 * Calendar-part arithmetic for a budget's period, used by the define-budget form and the budgets
 * list.
 *
 * The wire carries only year, month and day integers (ADR-028), never a moment in time, so every
 * function below works on those integers directly rather than routing a date string through a
 * `Date` and reading it back: `new Date(isoString)` reads a bare `YYYY-MM-DD` as UTC midnight,
 * and a caller's own zone can then name the wrong day (see `DateText`'s own comment for the same
 * bug). None of this touches money (ADR-029) - it is calendar arithmetic on year and month
 * integers, nothing more.
 */

/** A budget's period: the first day it covers, and the first day it no longer does (exclusive). */
export interface BudgetPeriod {
    /** ISO `YYYY-MM-DD`, the first day the period covers. */
    readonly start: string;
    /** ISO `YYYY-MM-DD`, exclusive: the period ends the day before this one. */
    readonly end: string;
}

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTH_INPUT = /^(\d{4})-(\d{2})$/;

/** A calendar date's parts, never a moment in time. */
interface DateParts {
    readonly year: number;
    /** 1-12. */
    readonly month: number;
    readonly day: number;
}

/**
 * `value` split into its year, month and day integers.
 *
 * @throws Error - when `value` is not `YYYY-MM-DD`.
 */
function parseIsoDate(value: string): DateParts {
    const match = ISO_DATE.exec(value);
    if (match === null) {
        throw new Error('expected an ISO date, YYYY-MM-DD');
    }
    return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
}

function formatIsoDate({ year, month, day }: DateParts): string {
    const y = String(year).padStart(4, '0');
    const m = String(month).padStart(2, '0');
    const d = String(day).padStart(2, '0');
    return `${y}-${m}-${d}`;
}

/** Whether `year` is a leap year in the Gregorian calendar. */
function isLeapYear(year: number): boolean {
    return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31] as const;

/** The number of days `month` (1-12) has in `year`, February counted for leap years. */
function daysInMonth(year: number, month: number): number {
    if (month === 2 && isLeapYear(year)) {
        return 29;
    }
    const days = DAYS_IN_MONTH[month - 1];
    if (days === undefined) {
        throw new Error('month must be between 1 and 12');
    }
    return days;
}

/** The year and month after `parts`', rolling December into next January. */
function monthAfter(parts: { readonly year: number; readonly month: number }): {
    year: number;
    month: number;
} {
    return parts.month === 12
        ? { year: parts.year + 1, month: 1 }
        : { year: parts.year, month: parts.month + 1 };
}

/**
 * A native month input's value as the exclusive-end period `DefineBudgetRequest` expects.
 *
 * @param month - `YYYY-MM`, such as `"2026-09"`.
 * @returns the calendar month as a period, such as `{start: "2026-09-01", end: "2026-10-01"}`.
 *   December rolls into next January.
 * @throws Error - when `month` is not `YYYY-MM`.
 */
export function monthInputToPeriod(month: string): BudgetPeriod {
    const match = MONTH_INPUT.exec(month);
    if (match === null) {
        throw new Error('expected a month input value, YYYY-MM');
    }
    const start: DateParts = { year: Number(match[1]), month: Number(match[2]), day: 1 };
    const end: DateParts = { ...monthAfter(start), day: 1 };
    return { start: formatIsoDate(start), end: formatIsoDate(end) };
}

/**
 * The calendar day before `date`, following into the last day of the previous month (and year,
 * where December is that month) when `date` is the first.
 *
 * Pure integer arithmetic over the parsed parts; no `Date` is constructed.
 */
function dayBefore(date: string): string {
    const parts = parseIsoDate(date);
    if (parts.day > 1) {
        return formatIsoDate({ ...parts, day: parts.day - 1 });
    }
    const month = parts.month === 1 ? 12 : parts.month - 1;
    const year = parts.month === 1 ? parts.year - 1 : parts.year;
    return formatIsoDate({ year, month, day: daysInMonth(year, month) });
}

/**
 * `period`'s display end: the last day it actually covers, since `period.end` is exclusive.
 *
 * @param period - the period to display.
 * @returns `period.end` minus one calendar day.
 */
export function periodDisplayEnd(period: BudgetPeriod): string {
    return dayBefore(period.end);
}

/**
 * Whether `period` covers exactly one calendar month: `start` is the first of a month and `end`
 * is the first of the next.
 */
export function isSingleCalendarMonth(period: BudgetPeriod): boolean {
    const start = parseIsoDate(period.start);
    const end = parseIsoDate(period.end);
    if (start.day !== 1 || end.day !== 1) {
        return false;
    }
    const expected = monthAfter(start);
    return expected.year === end.year && expected.month === end.month;
}

/** The locale every amount and date on screen is formatted in (see `@/shared/ui/locale.ts`). */
const DISPLAY_LOCALE = 'en-IL';

/**
 * `period`'s month label, such as "September 2026", formatted with `Intl.DateTimeFormat` from its
 * calendar parts.
 *
 * The instant handed to the formatter is built with `Date.UTC` from the parsed year and month -
 * never from `new Date(period.start)`, which would parse the string itself - and the formatter is
 * pinned to `timeZone: 'UTC'`, the same pairing `DateText` uses, so the label reads the same
 * month regardless of the browser's own zone.
 *
 * @param period - the period to label.
 * @returns the label when `period` is a single calendar month; `undefined` otherwise, so the
 *   caller falls back to showing the start and (display) end dates.
 */
export function periodMonthLabel(period: BudgetPeriod): string | undefined {
    if (!isSingleCalendarMonth(period)) {
        return undefined;
    }
    const { year, month } = parseIsoDate(period.start);
    const instant = new Date(Date.UTC(year, month - 1, 1));
    return new Intl.DateTimeFormat(DISPLAY_LOCALE, {
        month: 'long',
        year: 'numeric',
        timeZone: 'UTC',
    }).format(instant);
}

/**
 * `period`'s label for plain text, such as a document title: `periodMonthLabel` when it applies,
 * otherwise its start and display end, ISO to ISO.
 *
 * @param period - the period to label.
 * @returns a label that is always a string, unlike `periodMonthLabel`.
 */
export function periodLabel(period: BudgetPeriod): string {
    return periodMonthLabel(period) ?? `${period.start} to ${periodDisplayEnd(period)}`;
}
