// @vitest-environment node
// Pure string mapping: no DOM needed.

import { describe, expect, it } from 'vitest';

import { entryRowAmount } from './transaction';

describe('entryRowAmount', () => {
    it('Given_only_a_debit_When_mapped_Then_the_debit_string_is_kept_as_is', () => {
        expect(entryRowAmount('250.00', '')).toBe('250.00');
    });

    it('Given_only_a_credit_When_mapped_Then_it_becomes_the_negative_of_that_string', () => {
        expect(entryRowAmount('', '250.00')).toBe('-250.00');
    });

    it('Given_a_credit_already_carrying_a_leading_minus_When_mapped_Then_the_sign_is_stripped_before_one_is_added_back', () => {
        // The sign must never be doubled into "--250.00".
        expect(entryRowAmount('', '-250.00')).toBe('-250.00');
    });

    it('Given_both_a_debit_and_a_credit_When_mapped_Then_it_is_a_field_error', () => {
        expect(entryRowAmount('100.00', '50.00')).toEqual({ message: 'Enter a debit or a credit' });
    });

    it('Given_neither_a_debit_nor_a_credit_When_mapped_Then_it_is_a_field_error', () => {
        expect(entryRowAmount('', '')).toEqual({ message: 'Enter a debit or a credit' });
    });
});
