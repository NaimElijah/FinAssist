// @vitest-environment node
// Pure functions over plain data: no DOM needed.

import { describe, expect, it } from 'vitest';

import { buildRecurrence, type Recurrence, recurrenceSummary } from './recurrence';

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
});
