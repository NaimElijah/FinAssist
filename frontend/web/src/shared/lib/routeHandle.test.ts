// @vitest-environment node
// Reads plain match objects; no DOM and no router are involved.

import { describe, expect, it } from 'vitest';

import { deepestRouteTitle } from './routeHandle';

describe('deepestRouteTitle', () => {
    it('Given_an_area_and_its_screen_both_titled_When_read_Then_the_screens_title_wins', () => {
        expect(
            deepestRouteTitle([
                { handle: undefined },
                { handle: { title: 'Planning' } },
                { handle: { title: 'Scenarios' } },
            ]),
        ).toBe('Scenarios');
    });

    it('Given_the_deepest_match_declares_no_title_When_read_Then_the_deepest_titled_one_wins', () => {
        // A pathless layout or boundary route between the area and its screen carries no title.
        expect(
            deepestRouteTitle([
                { handle: { title: 'Budgets' } },
                { handle: { title: 7 } },
                { handle: null },
                { handle: {} },
            ]),
        ).toBe('Budgets');
    });

    it('Given_no_match_declares_a_string_title_When_read_Then_there_is_none', () => {
        expect(deepestRouteTitle([{ handle: undefined }, { handle: { title: 7 } }])).toBe(
            undefined,
        );
        expect(deepestRouteTitle([])).toBe(undefined);
    });
});
