// @vitest-environment jsdom
// Reads the rendered alert and clicks its retry, so it needs a DOM.

import { render, screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../api/client';
import { QueryErrorState } from './QueryErrorState';

const URL = '/ledger/api/v1/accounts';

/** A server failure carrying a problem whose detail the alert shows. */
function serverFailure(): ApiError {
    return new ApiError(500, URL, 'The ledger failed.', {
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The ledger failed.',
    });
}

describe('QueryErrorState', () => {
    it('Given_a_read_that_has_not_failed_When_rendered_Then_nothing_is_rendered', () => {
        const { container } = render(
            <QueryErrorState error={null} retrying={false} onRetry={vi.fn<() => void>()} />,
        );

        expect(container.childNodes).toHaveLength(0);
    });

    it('Given_a_failed_read_When_rendered_Then_the_alert_shows_the_failure_and_offers_a_retry', () => {
        render(
            <QueryErrorState
                error={serverFailure()}
                retrying={false}
                onRetry={vi.fn<() => void>()}
            />,
        );

        const alert = screen.getByRole('alert');
        expect(within(alert).getByText('The ledger failed.')).toBeDefined();
        expect(within(alert).getByRole('button', { name: 'Try again' })).toBeDefined();
    });

    it('Given_a_failed_read_When_Try_again_is_clicked_Then_the_read_is_sent_again', async () => {
        const onRetry = vi.fn<() => void>();
        render(<QueryErrorState error={serverFailure()} retrying={false} onRetry={onRetry} />);

        await userEvent
            .setup({ delay: null })
            .click(screen.getByRole('button', { name: 'Try again' }));

        expect(onRetry).toHaveBeenCalledTimes(1);
    });

    it('Given_a_ref_When_rendered_Then_it_names_the_alert', () => {
        const ref = createRef<HTMLDivElement>();

        render(
            <QueryErrorState
                ref={ref}
                error={serverFailure()}
                retrying={false}
                onRetry={vi.fn<() => void>()}
            />,
        );

        expect(ref.current).toBe(screen.getByRole('alert'));
    });
});
