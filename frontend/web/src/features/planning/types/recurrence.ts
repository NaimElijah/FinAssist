/**
 * How a planned flow repeats: once, until a date, or a number of times, at a weekly, monthly or
 * yearly cadence.
 *
 * No arithmetic: `interval` and `count` are counts, each wholly a positive safe integer, never
 * money (ADR-029). `recurrenceSummary` and `buildRecurrence` are pure and used both by the flows
 * table and the plan-flow form.
 */
import { formatCalendarDate } from '@/shared/lib/calendarDate';
import { DISPLAY_LOCALE } from '@/shared/lib/locale';

/** The three cadences a repeating flow can step at, in the order the form offers them. */
export const CADENCES = ['WEEKLY', 'MONTHLY', 'YEARLY'] as const;

/** One of the three cadences. */
export type Cadence = (typeof CADENCES)[number];

/** The words offered for each cadence in the plan-flow form's select. */
export const CADENCE_LABELS: Readonly<Record<Cadence, string>> = {
    WEEKLY: 'Weekly',
    MONTHLY: 'Monthly',
    YEARLY: 'Yearly',
};

/** A cadence's singular and plural nouns, for `recurrenceSummary`'s "every …" phrase. */
const CADENCE_WORDS: Readonly<
    Record<Cadence, { readonly singular: string; readonly plural: string }>
> = {
    WEEKLY: { singular: 'week', plural: 'weeks' },
    MONTHLY: { singular: 'month', plural: 'months' },
    YEARLY: { singular: 'year', plural: 'years' },
};

/**
 * How a planned flow repeats (ADR-028: a discriminated shape). Members that do not apply to
 * `kind` are absent, never present as `null` or `undefined`.
 */
export type Recurrence =
    | { readonly kind: 'ONCE' }
    | {
          readonly kind: 'UNTIL';
          readonly cadence: Cadence;
          readonly interval: number;
          /** An ISO `YYYY-MM-DD` date, exclusive: the last occurrence falls before it. */
          readonly until: string;
      }
    | {
          readonly kind: 'TIMES';
          readonly cadence: Cadence;
          readonly interval: number;
          readonly count: number;
      };

/** The three ways the plan-flow form offers to repeat a flow, in the order it offers them. */
export const RECURRENCE_KINDS = ['ONCE', 'UNTIL', 'TIMES'] as const;

/** One of the three ways a flow can repeat. */
export type RecurrenceKind = (typeof RECURRENCE_KINDS)[number];

/** The words offered for each kind in the plan-flow form's Repeats select. */
export const RECURRENCE_KIND_LABELS: Readonly<Record<RecurrenceKind, string>> = {
    ONCE: 'Once',
    UNTIL: 'Until a date',
    TIMES: 'A number of times',
};

/**
 * The plan-flow form's raw recurrence fields, before `buildRecurrence` maps them: every field a
 * string, exactly as its input holds it, whichever `kind` is chosen. Fields that do not apply to
 * the chosen `kind` are simply not read by `buildRecurrence`.
 */
export interface RecurrenceFormState {
    readonly kind: RecurrenceKind;
    readonly cadence: Cadence;
    /** An integer string, such as `"1"` or `"2"`. */
    readonly interval: string;
    /** An ISO `YYYY-MM-DD` date, read only when `kind` is `'UNTIL'`. */
    readonly until: string;
    /** An integer string, read only when `kind` is `'TIMES'`. */
    readonly count: string;
}

/** A recurrence member `buildRecurrence` reads as a count: the steps between, or how many times. */
export type CountField = 'interval' | 'count';

/**
 * Thrown by `buildRecurrence` when a count it needs is not a positive safe integer. It names the
 * member, so the plan-flow form can show the refusal on the field it came from.
 */
export class InvalidCountError extends Error {
    /** The member whose text is not wholly a positive safe integer. */
    readonly field: CountField;

    constructor(field: CountField, value: string) {
        super(`Expected ${field} to be a positive integer, got "${value}"`);
        this.name = 'InvalidCountError';
        this.field = field;
    }
}

/**
 * A positive integer count as an HTML `pattern`: digits only, no leading zero and no zero itself,
 * since the ledger's own domain constructors refuse a count below one. The plan-flow form sets it
 * on Every and Times, and `buildRecurrence` matches it too, so a value the form refuses is refused
 * here as well.
 */
export const POSITIVE_INTEGER_PATTERN = '[1-9][0-9]*';

/** `POSITIVE_INTEGER_PATTERN` held to the whole text, as the browser holds a `pattern`. */
const POSITIVE_INTEGER = new RegExp(`^(?:${POSITIVE_INTEGER_PATTERN})$`);

/**
 * `value` parsed as a positive safe integer count, never money (ADR-029).
 *
 * Matched whole before it is parsed: `Number.parseInt` reads the leading digits and ignores the
 * rest, so on its own it would read `"1.5"`, `"12abc"` and `"1e3"` as 1, 12 and 1. Then held to a
 * safe integer, since digits alone can exceed what a number holds exactly: past 2^53 - 1 a count
 * would be sent rounded, and past about 1.8e308 as `Infinity`, which JSON writes as `null`.
 *
 * @throws InvalidCountError - when `value` is not wholly a positive safe integer.
 */
function parseCount(value: string, field: CountField): number {
    const parsed = POSITIVE_INTEGER.test(value) ? Number.parseInt(value, 10) : Number.NaN;
    if (!Number.isSafeInteger(parsed)) {
        throw new InvalidCountError(field, value);
    }
    return parsed;
}

/**
 * Map the plan-flow form's raw recurrence fields to the `Recurrence` the ledger expects, omitting
 * whichever members `kind` does not carry.
 *
 * `interval` and `count` are parsed as counts rather than money (ADR-029), each a positive safe
 * integer. Native `required` and a digit `pattern` on both fields (see `PlanFlowForm`) keep an
 * unparsable string from reaching here through the UI, but a count past
 * `Number.MAX_SAFE_INTEGER` matches the pattern, so this alone refuses it, as it refuses a bad
 * count that arrives another way.
 *
 * @param form - the form's current recurrence fields.
 * @returns the mapped `Recurrence`, holding exactly the members its `kind` carries.
 * @throws InvalidCountError - when `interval` or `count` (whichever `kind` needs) is not wholly a
 *   positive safe integer, naming which; an empty, zero, signed, fractional or exponent value, one
 *   with a leading zero, whitespace or trailing text, or one above `Number.MAX_SAFE_INTEGER`, is
 *   refused rather than sent.
 */
export function buildRecurrence(form: RecurrenceFormState): Recurrence {
    if (form.kind === 'ONCE') {
        return { kind: 'ONCE' };
    }
    const interval = parseCount(form.interval, 'interval');
    if (form.kind === 'UNTIL') {
        return { kind: 'UNTIL', cadence: form.cadence, interval, until: form.until };
    }
    return {
        kind: 'TIMES',
        cadence: form.cadence,
        interval,
        count: parseCount(form.count, 'count'),
    };
}

/** A long calendar date, such as "1 January 2027", in UTC as `formatCalendarDate` requires. */
const LONG_DATE_FORMAT = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
});

/**
 * A human summary of how a flow repeats, for the flows table's Recurrence column: "Once", "Every
 * month, 12 times", or "Every 2 weeks until 1 January 2027". An `interval` above 1 pluralises the
 * cadence.
 *
 * @param recurrence - the recurrence to describe.
 * @returns the summary sentence.
 */
export function recurrenceSummary(recurrence: Recurrence): string {
    if (recurrence.kind === 'ONCE') {
        return 'Once';
    }
    const words = CADENCE_WORDS[recurrence.cadence];
    const everyPhrase =
        recurrence.interval > 1
            ? `Every ${String(recurrence.interval)} ${words.plural}`
            : `Every ${words.singular}`;
    return recurrence.kind === 'TIMES'
        ? `${everyPhrase}, ${String(recurrence.count)} times`
        : `${everyPhrase} until ${formatCalendarDate(recurrence.until, LONG_DATE_FORMAT)}`;
}
