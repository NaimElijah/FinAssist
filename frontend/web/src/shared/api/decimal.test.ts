// @vitest-environment node
// A pure predicate over plain values: no DOM needed.

import { describe, expect, it } from 'vitest';

import { isDecimalString } from './decimal';

describe('isDecimalString', () => {
    for (const value of ['0', '3', '-12.00', '1250.50', '123456789012345678901234567890.01']) {
        it(`Given_${JSON.stringify(value)}_When_tested_Then_it_is_a_decimal_string`, () => {
            expect(isDecimalString(value)).toBe(true);
        });
    }

    for (const value of ['', '1,250.50', '1e3', '.5', '5.', '+1', ' 1', 'NaN', '--1']) {
        it(`Given_${JSON.stringify(value)}_When_tested_Then_it_is_not_a_decimal_string`, () => {
            expect(isDecimalString(value)).toBe(false);
        });
    }

    it('Given_a_JSON_number_When_tested_Then_it_is_refused_though_its_text_would_match', () => {
        // JSON.parse has already rounded a number by the time any code sees it (ADR-028).
        expect(isDecimalString(12)).toBe(false);
    });
});
