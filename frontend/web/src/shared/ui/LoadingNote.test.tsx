// @vitest-environment jsdom
// Reads the rendered status region, so it needs a DOM.

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { LoadingNote } from './LoadingNote';

describe('LoadingNote', () => {
    it('Given_a_message_When_rendered_Then_a_status_region_says_it', async () => {
        render(<LoadingNote message="Loading accounts…" />);

        await waitFor(() => {
            expect(screen.getByRole('status').textContent).toBe('Loading accounts…');
        });
    });

    it('Given_a_message_When_first_rendered_Then_its_status_region_is_empty_until_the_same_region_takes_the_message', async () => {
        // A live region announces a change to what it holds, so it must be on the page before its
        // text arrives; one inserted with its text already in it is often read out by nothing.
        render(<LoadingNote message="Loading accounts…" />);

        const region = screen.getByRole('status');
        expect(region.textContent).toBe('');
        await waitFor(() => {
            expect(region.textContent).toBe('Loading accounts…');
        });
        expect(screen.getByRole('status')).toBe(region);
    });
});
