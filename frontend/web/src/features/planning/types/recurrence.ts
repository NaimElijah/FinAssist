/**
 * How a planned flow repeats: once, until a date, or a number of times, at a weekly, monthly or
 * yearly cadence.
 *
 * No arithmetic: `interval` and `count` are counts, parsed with `Number.parseInt`, never money
 * (ADR-029). `recurrenceSummary` and `buildRecurrence` are pure and used both by the flows table
 * and the plan-flow form.
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
 * Thrown by `buildRecurrence` when a count it needs is not a positive integer. It names the member,
 * so the plan-flow form can show the refusal on the field it came from.
 */
export class InvalidCountError extends Error {
    /** The member that did not parse to a positive integer. */
    readonly field: CountField;

    constructor(field: CountField, value: string) {
        super(`Expected ${field} to be a positive integer, got "${value}"`);
        this.name = 'InvalidCountError';
        this.field = field;
    }
}

/**
 * `value` parsed as a positive integer count, never money (ADR-029).
 *
 * @throws InvalidCountError - when `value` does not parse to a positive integer.
 */
function parseCount(value: string, field: CountField): number {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isInteger(parsed) || parsed <= 0) {
        throw new InvalidCountError(field, value);
    }
    return parsed;
}

/**
 * Map the plan-flow form's raw recurrence fields to the `Recurrence` the ledger expects, omitting
 * whichever members `kind` does not carry.
 *
 * `interval` and `count` are parsed with `Number.parseInt`, as counts rather than money
 * (ADR-029); native `required` and a digit `pattern` on both fields (see `PlanFlowForm`) keep an
 * unparsable string from reaching here through the UI.
 *
 * @param form - the form's current recurrence fields.
 * @returns the mapped `Recurrence`, holding exactly the members its `kind` carries.
 * @throws InvalidCountError - when `interval` or `count` (whichever `kind` needs) does not parse,
 *   by `Number.parseInt`, to a positive integer, naming which; a value that parses to `NaN` or
 *   zero is refused rather than sent.
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
