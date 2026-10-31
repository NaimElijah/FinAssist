// @vitest-environment jsdom
// Resolves aria-describedby ids against a document, so it needs a DOM.

import { afterEach, describe, expect, it } from 'vitest';

import { accessibleDescriptionOf } from './descriptions';

/**
 * Append a paragraph per entry of `describing`, keyed by id and holding its text, then a control
 * described by `describedBy`, and return the control.
 */
function mounted(describing: Readonly<Record<string, string>>, describedBy: string): Element {
    for (const [id, text] of Object.entries(describing)) {
        const paragraph = document.createElement('p');
        paragraph.id = id;
        paragraph.textContent = text;
        document.body.append(paragraph);
    }
    const control = document.createElement('input');
    control.setAttribute('aria-describedby', describedBy);
    document.body.append(control);
    return control;
}

afterEach(() => {
    document.body.replaceChildren();
});

describe('accessibleDescriptionOf', () => {
    it('Given_describing_text_that_wraps_across_lines_When_read_Then_its_whitespace_collapses_to_single_spaces', () => {
        // A screen reader reads a run of whitespace as one space, so a test asserting the words
        // must not depend on how the markup happened to be indented.
        const control = mounted({ hint: '  Choose   2\n      to 10\tscenarios.  ' }, 'hint');

        expect(accessibleDescriptionOf(control)).toBe('Choose 2 to 10 scenarios.');
    });

    it('Given_several_describing_elements_When_read_Then_their_texts_join_in_the_order_named', () => {
        const control = mounted({ error: 'Too few.', hint: 'Choose 2 to 10.' }, 'hint  error');

        expect(accessibleDescriptionOf(control)).toBe('Choose 2 to 10. Too few.');
    });

    it('Given_an_id_that_matches_nothing_When_read_Then_it_throws', () => {
        const control = mounted({}, 'missing');

        expect(() => accessibleDescriptionOf(control)).toThrow(/"missing"/);
    });
});
