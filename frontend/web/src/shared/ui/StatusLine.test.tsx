// @vitest-environment jsdom
// Reads the rendered status region, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StatusLine } from './StatusLine';

describe('StatusLine', () => {
    it('Given_no_message_When_rendered_Then_an_empty_status_region_is_already_on_the_page', () => {
        // A live region announces a change to what it holds only once it is on the page, so the
        // region must be there before its first message.
        render(<StatusLine message="" />);

        expect(screen.getByRole('status').textContent).toBe('');
    });

    it('Given_a_message_When_it_replaces_an_empty_one_Then_the_same_region_says_it', () => {
        const { rerender } = render(<StatusLine message="" />);
        const region = screen.getByRole('status');

        rerender(<StatusLine message="Entry 3 added." />);

        expect(screen.getByRole('status')).toBe(region);
        expect(region.textContent).toBe('Entry 3 added.');
    });
});
