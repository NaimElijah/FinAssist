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

    it('Given_a_day_that_does_not_exist_When_formatted_Then_the_error_names_this_function_and_never_the_value', () => {
        // Matched whole, so the value - which a render error's message can carry into the logs -
        // is shown to be left out.
        expect(() => formatCalendarDate('2026-02-30')).toThrow(
            /^formatCalendarDate was given a value that is not a calendar date$/,
        );
    });
});
