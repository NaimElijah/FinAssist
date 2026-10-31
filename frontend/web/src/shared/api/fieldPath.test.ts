import { describe, expect, it } from 'vitest';

import { indexedField } from './fieldPath';

describe('indexedField', () => {
    it('Given_a_member_of_a_list_element_When_read_Then_its_one_based_position_and_member_are_returned', () => {
        expect(indexedField('entries[0].amount', 'entries')).toEqual({
            position: 1,
            member: 'amount',
        });
    });

    it('Given_a_nested_member_of_a_list_element_When_read_Then_the_whole_remaining_path_is_the_member', () => {
        expect(indexedField('limits[2].limit.amount', 'limits')).toEqual({
            position: 3,
            member: 'limit.amount',
        });
    });

    it('Given_a_list_element_itself_When_read_Then_it_has_a_position_and_no_member', () => {
        expect(indexedField('flows[4]', 'flows')).toEqual({ position: 5, member: undefined });
    });

    it('Given_the_list_itself_When_read_Then_no_element_is_named', () => {
        expect(indexedField('entries', 'entries')).toBeUndefined();
    });

    it('Given_an_element_of_another_list_When_read_Then_no_element_is_named', () => {
        expect(indexedField('limits[0].category', 'entries')).toBeUndefined();
    });

    it('Given_a_list_whose_name_only_ends_with_the_one_asked_for_When_read_Then_no_element_is_named', () => {
        expect(indexedField('subentries[0].amount', 'entries')).toBeUndefined();
    });

    it('Given_an_index_that_is_not_a_number_When_read_Then_no_element_is_named', () => {
        expect(indexedField('entries[x].amount', 'entries')).toBeUndefined();
    });
});
