// @vitest-environment node
// Formats with Intl alone; no DOM is involved.

import { describe, expect, it } from 'vitest';

import { formatCalendarDate } from './calendarDate';

describe('formatCalendarDate', () => {
    it('Given_a_value_not_shaped_YYYY_MM_DD_When_formatted_Then_the_error_names_this_function_and_the_shape', () => {
        // It is called outside DateText too, such as for a row action's name, so naming DateText
        // would send whoever reads the log to the wrong place.
        expect(() => formatCalendarDate('01/09/2026')).toThrow(
            /^formatCalendarDate expects an ISO date, YYYY-MM-DD$/,
        );
    });

    // Date.UTC reads a year from 0 to 99 as 1900 to 1999, so a two-digit year must reach the
    // formatter as itself; the ledger accepts every year from 1, and a date input yields 0026 from
    // a typed "26".
    for (const [value, shown] of [
        ['0026-09-01', '1 Sept 26'],
        ['0099-12-31', '31 Dec 99'],
        ['0001-01-01', '1 Jan 1'],
    ] as const) {
        it(`Given_year_${value.slice(0, 4)}_When_${value}_is_formatted_Then_it_shows_that_year_not_a_1900s_one`, () => {
            expect(formatCalendarDate(value)).toBe(shown);
        });
    }

    it('Given_a_formatter_pinned_to_UTC_When_a_date_is_formatted_with_it_Then_it_uses_that_formatter', () => {
        const longDate = new Intl.DateTimeFormat('en-IL', {
            day: 'numeric',
            month: 'long',
            year: 'numeric',
            timeZone: 'UTC',
        });

        expect(formatCalendarDate('0027-01-01', longDate)).toBe('1 January 27');
    });

    it('Given_a_formatter_not_pinned_to_UTC_When_a_date_is_formatted_with_it_Then_it_throws', () => {
        // The instant is UTC midnight; any other zone can name the previous day.
        const localZone = new Intl.DateTimeFormat('en-IL', {
            dateStyle: 'medium',
            timeZone: 'America/Los_Angeles',
        });

        expect(() => formatCalendarDate('2026-09-01', localZone)).toThrow(
            /^formatCalendarDate needs a formatter pinned to timeZone UTC$/,
        );
    });

    it('Given_a_day_that_does_not_exist_When_formatted_Then_the_error_names_this_function_and_never_the_value', () => {
        // Matched whole, so the value - which a render error's message can carry into the logs -
        // is shown to be left out.
        expect(() => formatCalendarDate('2026-02-30')).toThrow(
            /^formatCalendarDate was given a value that is not a calendar date$/,
        );
    });
});
