// @vitest-environment node
// Pure string work: no DOM, no fetch.

import { describe, expect, it } from 'vitest';

import { pathSegment } from './pathSegment';

describe('pathSegment', () => {
    it.each([
        ['an_empty_value', ''],
        ['a_dot', '.'],
        ['a_dot_dot', '..'],
    ])('Given_%s_When_made_a_path_segment_Then_it_throws_a_RangeError', (_label, value) => {
        // Each would shift the path by a segment: `a//b` and `a/./b` name `a/b`, and `a/../b`
        // names `b`, once the URL is resolved.
        expect(() => pathSegment(value)).toThrow(RangeError);
    });

    it.each([
        ['an_ordinary_id', 'acc-1', 'acc-1'],
        ['a_dot_inside_it', 'BRK.B', 'BRK.B'],
        ['three_dots', '...', '...'],
        ['a_slash', 'BRK/B', 'BRK%2FB'],
        ['a_space', 'BRK B', 'BRK%20B'],
        ['an_encoded_dot_dot', '%2e%2e', '%252e%252e'],
    ])(
        'Given_%s_When_made_a_path_segment_Then_it_is_percent_encoded_as_one_segment',
        (_label, value, segment) => {
            expect(pathSegment(value)).toBe(segment);
        },
    );
});
