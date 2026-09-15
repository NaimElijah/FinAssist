// @vitest-environment node
// Pure string mapping: no DOM needed.

import { describe, expect, it } from 'vitest';

import { buildEntries, entryRowAmount } from './transaction';

const CHECKING = { id: 'checking-id', name: 'Checking', type: 'ASSET', currency: 'ILS' } as const;
const SAVINGS_USD = { id: 'savings-id', name: 'Savings', type: 'ASSET', currency: 'USD' } as const;

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

    it('Given_a_debit_typed_with_a_leading_minus_When_mapped_Then_the_sign_is_stripped_and_it_posts_positive', () => {
        // Ruling 35: both columns take an unsigned magnitude. The column alone carries the sign -
        // debit positive, credit negative - so a stray '-' under Debit must not flip it to a credit.
        expect(entryRowAmount('-250.00', '')).toBe('250.00');
    });

    it('Given_both_a_debit_and_a_credit_When_mapped_Then_it_is_a_field_error', () => {
        expect(entryRowAmount('100.00', '50.00')).toEqual({ message: 'Enter a debit or a credit' });
    });

    it('Given_neither_a_debit_nor_a_credit_When_mapped_Then_it_is_a_field_error', () => {
        expect(entryRowAmount('', '')).toEqual({ message: 'Enter a debit or a credit' });
    });
});

describe('buildEntries', () => {
    it('Given_accounts_in_different_currencies_When_built_Then_each_entrys_currency_is_its_own_accounts', () => {
        const rows = [
            { key: 0, accountId: CHECKING.id, debit: '250.00', credit: '' },
            { key: 1, accountId: SAVINGS_USD.id, debit: '', credit: '250.00' },
        ];

        const built = buildEntries(rows, [CHECKING, SAVINGS_USD]);

        expect(built).toEqual({
            entries: [
                { account: CHECKING.id, amount: { amount: '250.00', currency: 'ILS' } },
                { account: SAVINGS_USD.id, amount: { amount: '-250.00', currency: 'USD' } },
            ],
        });
    });

    it('Given_an_invalid_row_When_built_Then_its_error_is_keyed_by_the_rows_own_stable_key', () => {
        const rows = [
            { key: 7, accountId: CHECKING.id, debit: '10.00', credit: '' },
            { key: 3, accountId: SAVINGS_USD.id, debit: '10.00', credit: '20.00' },
        ];

        const built = buildEntries(rows, [CHECKING, SAVINGS_USD]);

        expect(built).toEqual({ rowErrors: new Map([[3, 'Enter a debit or a credit']]) });
    });

    it('Given_a_row_naming_no_account_among_those_offered_When_built_Then_it_throws', () => {
        // Unreachable through the form itself: the Account select is `required` and offers only
        // accounts from this same list. Reaching this is a bug in the caller.
        const rows = [{ key: 0, accountId: 'not-a-real-account-id', debit: '10.00', credit: '' }];

        expect(() => buildEntries(rows, [CHECKING])).toThrow(/account/i);
    });
});
