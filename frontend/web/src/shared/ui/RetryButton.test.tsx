// @vitest-environment jsdom
// Clicks the rendered button, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { RetryButton } from './RetryButton';

describe('RetryButton', () => {
    it('Given_no_retry_in_flight_When_clicked_Then_the_read_is_sent_again_once', async () => {
        const onRetry = vi.fn<() => void>();
        render(<RetryButton retrying={false} onRetry={onRetry} />);

        await userEvent
            .setup({ delay: null })
            .click(screen.getByRole('button', { name: 'Try again' }));

        expect(onRetry).toHaveBeenCalledTimes(1);
    });

    it('Given_a_retry_in_flight_When_rendered_Then_the_button_is_disabled_and_busy', () => {
        render(<RetryButton retrying onRetry={vi.fn<() => void>()} />);

        const button = screen.getByRole('button', { name: 'Try again' });
        expect(button).toHaveProperty('disabled', true);
        expect(button.getAttribute('aria-busy')).toBe('true');
    });
});
