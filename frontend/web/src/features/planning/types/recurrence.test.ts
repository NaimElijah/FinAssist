// @vitest-environment node
// Pure functions over plain data: no DOM needed.

import { describe, expect, it } from 'vitest';

import {
    buildRecurrence,
    InvalidCountError,
    type Recurrence,
    recurrenceSummary,
} from './recurrence';

/** What `action` throws, or `undefined` when it returns normally. */
function thrownBy(action: () => unknown): unknown {
    try {
        action();
    } catch (error) {
        return error;
    }
    return undefined;
}

describe('recurrenceSummary', () => {
    it('Given_a_once_recurrence_When_summarised_Then_it_reads_Once', () => {
        expect(recurrenceSummary({ kind: 'ONCE' })).toBe('Once');
    });

    it('Given_a_times_recurrence_with_an_interval_of_one_When_summarised_Then_the_cadence_is_singular', () => {
        const recurrence: Recurrence = {
            kind: 'TIMES',
            cadence: 'MONTHLY',
            interval: 1,
            count: 12,
        };

        expect(recurrenceSummary(recurrence)).toBe('Every month, 12 times');
    });

    it('Given_an_until_recurrence_with_an_interval_above_one_When_summarised_Then_the_cadence_is_pluralised', () => {
        const recurrence: Recurrence = {
            kind: 'UNTIL',
            cadence: 'WEEKLY',
            interval: 2,
            until: '2027-01-01',
        };

        expect(recurrenceSummary(recurrence)).toBe('Every 2 weeks until 1 January 2027');
    });

    it('Given_a_times_recurrence_with_an_interval_above_one_When_summarised_Then_the_cadence_is_pluralised', () => {
        const recurrence: Recurrence = { kind: 'TIMES', cadence: 'YEARLY', interval: 3, count: 5 };

        expect(recurrenceSummary(recurrence)).toBe('Every 3 years, 5 times');
    });

    it('Given_an_until_recurrence_with_an_interval_of_one_When_summarised_Then_the_cadence_is_singular', () => {
        const recurrence: Recurrence = {
            kind: 'UNTIL',
            cadence: 'MONTHLY',
            interval: 1,
            until: '2026-12-31',
        };

        expect(recurrenceSummary(recurrence)).toBe('Every month until 31 December 2026');
    });

    it('Given_an_until_date_in_a_year_below_100_When_summarised_Then_it_names_that_year_not_one_in_the_1900s', () => {
        // A date field accepts year 27; `Date.UTC` alone would read it as 1927.
        const recurrence: Recurrence = {
            kind: 'UNTIL',
            cadence: 'MONTHLY',
            interval: 1,
            until: '0027-01-01',
        };

        expect(recurrenceSummary(recurrence)).toBe('Every month until 1 January 27');
    });

    it('Given_an_until_date_that_does_not_exist_When_summarised_Then_it_throws_as_DateText_does', () => {
        // Rolling 30 February into 2 March would summarise a date the ledger never holds.
        const recurrence: Recurrence = {
            kind: 'UNTIL',
            cadence: 'MONTHLY',
            interval: 1,
            until: '2027-02-30',
        };

        expect(() => recurrenceSummary(recurrence)).toThrow(/not a calendar date/);
    });
});

describe('buildRecurrence', () => {
    it('Given_the_once_kind_When_built_Then_no_other_member_is_present', () => {
        const recurrence = buildRecurrence({
            kind: 'ONCE',
            cadence: 'MONTHLY',
            interval: '1',
            until: '',
            count: '',
        });

        expect(recurrence).toEqual({ kind: 'ONCE' });
        expect(Object.keys(recurrence)).toEqual(['kind']);
    });

    it('Given_the_until_kind_When_built_Then_interval_is_an_integer_and_count_is_absent', () => {
        const recurrence = buildRecurrence({
            kind: 'UNTIL',
            cadence: 'WEEKLY',
            interval: '2',
            until: '2027-01-01',
            count: '',
        });

        expect(recurrence).toEqual({
            kind: 'UNTIL',
            cadence: 'WEEKLY',
            interval: 2,
            until: '2027-01-01',
        });
        expect(Object.keys(recurrence)).toEqual(['kind', 'cadence', 'interval', 'until']);
    });

    it('Given_the_times_kind_When_built_Then_count_is_an_integer_and_until_is_absent', () => {
        const recurrence = buildRecurrence({
            kind: 'TIMES',
            cadence: 'MONTHLY',
            interval: '1',
            until: '',
            count: '12',
        });

        expect(recurrence).toEqual({ kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 });
        expect(Object.keys(recurrence)).toEqual(['kind', 'cadence', 'interval', 'count']);
    });

    it('Given_a_non_integer_interval_When_built_Then_it_throws_rather_than_send_NaN', () => {
        expect(() =>
            buildRecurrence({
                kind: 'TIMES',
                cadence: 'MONTHLY',
                interval: 'x',
                until: '',
                count: '12',
            }),
        ).toThrow();
    });

    it('Given_a_zero_count_When_built_Then_it_throws_rather_than_send_a_non_positive_count', () => {
        expect(() =>
            buildRecurrence({
                kind: 'TIMES',
                cadence: 'MONTHLY',
                interval: '1',
                until: '',
                count: '0',
            }),
        ).toThrow();
    });

    it('Given_a_zero_interval_When_built_Then_the_error_names_interval_as_the_member_refused', () => {
        // The form shows the refusal on the field it came from, so the error must say which.
        const thrown = thrownBy(() =>
            buildRecurrence({
                kind: 'TIMES',
                cadence: 'MONTHLY',
                interval: '0',
                until: '',
                count: '12',
            }),
        );

        expect(thrown).toBeInstanceOf(InvalidCountError);
        expect(thrown).toHaveProperty('field', 'interval');
    });

    it('Given_a_zero_count_When_built_Then_the_error_names_count_as_the_member_refused', () => {
        const thrown = thrownBy(() =>
            buildRecurrence({
                kind: 'TIMES',
                cadence: 'MONTHLY',
                interval: '1',
                until: '',
                count: '0',
            }),
        );

        expect(thrown).toBeInstanceOf(InvalidCountError);
        expect(thrown).toHaveProperty('field', 'count');
    });

    // `Number.parseInt` reads the leading digits and ignores the rest, so a scripted submit of
    // "1.5", "12abc" or "1e3" was sent as 1, 12 or 1 rather than refused as the form refuses it.
    it.each(['1.5', '12abc', '1e3', ' 12', '012'])(
        'Given_a_count_of_%j_that_is_not_wholly_a_positive_integer_When_built_Then_it_is_refused_rather_than_truncated',
        (count) => {
            const thrown = thrownBy(() =>
                buildRecurrence({
                    kind: 'TIMES',
                    cadence: 'MONTHLY',
                    interval: '1',
                    until: '',
                    count,
                }),
            );

            expect(thrown).toBeInstanceOf(InvalidCountError);
            expect(thrown).toHaveProperty('field', 'count');
        },
    );

    it.each(['1.5', '12abc', '1e3', ' 12', '012'])(
        'Given_an_interval_of_%j_that_is_not_wholly_a_positive_integer_When_built_Then_it_is_refused_rather_than_truncated',
        (interval) => {
            const thrown = thrownBy(() =>
                buildRecurrence({
                    kind: 'UNTIL',
                    cadence: 'MONTHLY',
                    interval,
                    until: '2027-01-01',
                    count: '',
                }),
            );

            expect(thrown).toBeInstanceOf(InvalidCountError);
            expect(thrown).toHaveProperty('field', 'interval');
        },
    );
});

describe('buildRecurrence beyond a safe integer', () => {
    // Digits alone pass the whole-text match, but a number past 2^53 - 1 no longer holds them:
    // 401 digits parse to Infinity, which JSON sends as null, and 2^53 + 1 reads as 2^53.
    const UNSAFE_COUNTS = [
        ['parses_to_Infinity', `1${'0'.repeat(400)}`],
        ['is_one_past_the_largest_safe_integer', String(Number.MAX_SAFE_INTEGER + 1)],
        ['loses_precision_when_parsed', '9007199254740993'],
    ] as const;

    it.each(UNSAFE_COUNTS)(
        'Given_a_count_that_%s_When_built_Then_it_is_refused_rather_than_sent_imprecisely',
        (_reason, count) => {
            const thrown = thrownBy(() =>
                buildRecurrence({
                    kind: 'TIMES',
                    cadence: 'MONTHLY',
                    interval: '1',
                    until: '',
                    count,
                }),
            );

            expect(thrown).toBeInstanceOf(InvalidCountError);
            expect(thrown).toHaveProperty('field', 'count');
        },
    );

    it.each(UNSAFE_COUNTS)(
        'Given_an_interval_that_%s_When_built_Then_it_is_refused_rather_than_sent_imprecisely',
        (_reason, interval) => {
            const thrown = thrownBy(() =>
                buildRecurrence({
                    kind: 'UNTIL',
                    cadence: 'MONTHLY',
                    interval,
                    until: '2027-01-01',
                    count: '',
                }),
            );

            expect(thrown).toBeInstanceOf(InvalidCountError);
            expect(thrown).toHaveProperty('field', 'interval');
        },
    );

    it('Given_a_count_past_the_largest_safe_integer_When_built_Then_the_error_states_the_safe_integer_rule', () => {
        // The count is a whole number of 1 or more, so a message stating only that rule would
        // not say why it was refused.
        const thrown = thrownBy(() =>
            buildRecurrence({
                kind: 'TIMES',
                cadence: 'MONTHLY',
                interval: '1',
                until: '',
                count: '9007199254740993',
            }),
        );

        expect(thrown).toHaveProperty(
            'message',
            'Expected count to be a positive safe integer, got "9007199254740993"',
        );
    });

    it('Given_a_count_of_the_largest_safe_integer_When_built_Then_it_is_kept_exactly', () => {
        const recurrence = buildRecurrence({
            kind: 'TIMES',
            cadence: 'MONTHLY',
            interval: '1',
            until: '',
            count: String(Number.MAX_SAFE_INTEGER),
        });

        expect(recurrence).toEqual({
            kind: 'TIMES',
            cadence: 'MONTHLY',
            interval: 1,
            count: Number.MAX_SAFE_INTEGER,
        });
    });
});
