// @vitest-environment jsdom
// Reads the rendered message and action, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
    it('Given_a_message_When_rendered_Then_it_is_shown', () => {
        render(<EmptyState message="No accounts yet." />);

        expect(screen.getByText('No accounts yet.')).toBeDefined();
    });

    it('Given_an_action_When_rendered_Then_it_is_shown_with_the_message', () => {
        render(<EmptyState message="No accounts yet." action={<button>Open an account</button>} />);

        expect(screen.getByRole('button', { name: 'Open an account' })).toBeDefined();
    });

    it('Given_no_action_When_rendered_Then_only_the_message_is_shown', () => {
        render(<EmptyState message="No accounts yet." />);

        expect(screen.queryByRole('button')).toBeNull();
    });
});
