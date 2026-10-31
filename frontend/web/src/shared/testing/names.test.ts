// @vitest-environment node
// Exercises the name matchers themselves, plain patterns against plain strings.

import { describe, expect, it } from 'vitest';

import { nameIsOrStartsWith, nameStartingWith } from './names';

describe('nameStartingWith', () => {
    it('Given_a_row_named_control_and_the_bare_word_When_matched_Then_only_the_row_named_one_matches', () => {
        // A lookup must find the row's control, never a panel's submit named by the word alone.
        expect(nameStartingWith('Clear').test('Clear Rent, 1 Sept 2026')).toBe(true);
        expect(nameStartingWith('Clear').test('Clear')).toBe(false);
    });
});

describe('nameIsOrStartsWith', () => {
    it('Given_the_bare_word_or_a_row_named_control_When_matched_Then_both_match', () => {
        // An absence check must fail on either form, or a control renamed to the bare word
        // would slip past it.
        expect(nameIsOrStartsWith('Clear').test('Clear')).toBe(true);
        expect(nameIsOrStartsWith('Clear').test('Clear Rent, 1 Sept 2026')).toBe(true);
    });

    it('Given_a_longer_word_or_the_word_later_in_the_name_When_matched_Then_neither_matches', () => {
        expect(nameIsOrStartsWith('Clear').test('Clearance')).toBe(false);
        expect(nameIsOrStartsWith('Clear').test('Not Clear')).toBe(false);
    });

    it('Given_a_word_with_a_regex_character_When_matched_Then_it_is_read_literally', () => {
        expect(nameIsOrStartsWith('Add (more)').test('Add (more)')).toBe(true);
        expect(nameIsOrStartsWith('Add (more)').test('Add more')).toBe(false);
    });
});
