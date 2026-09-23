// @vitest-environment jsdom
// Reads the rendered alert, so it needs a DOM.

import { render, screen, within } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';

import { ApiError, type Problem } from '../api/client';
import { ErrorState } from './ErrorState';

const GENERIC_SENTENCE =
    'Something went wrong. Try again, and reload the page if it keeps happening.';

const URL = '/ledger/api/v1/transactions';

function apiError(problem: Problem | undefined, status = 400): ApiError {
    return new ApiError(status, URL, 'server message', problem);
}

describe('ErrorState', () => {
    it('Given_an_ApiError_with_a_detail_and_field_errors_When_rendered_Then_the_alert_shows_both', () => {
        render(
            <ErrorState
                error={apiError({
                    status: 400,
                    code: 'VALIDATION_FAILED',
                    title: 'Bad request',
                    detail: 'The transaction is not valid.',
                    errors: [
                        { field: 'entries', message: 'must hold at least two entries' },
                        { field: 'memo', message: 'must not be blank' },
                    ],
                })}
            />,
        );

        const alert = screen.getByRole('alert');
        expect(within(alert).getByText('The transaction is not valid.')).toBeDefined();
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['entries: must hold at least two entries', 'memo: must not be blank']);
    });

    it('Given_a_problem_with_a_title_and_no_detail_When_rendered_Then_the_title_is_shown', () => {
        render(
            <ErrorState
                error={apiError(
                    { status: 409, code: 'ILLEGAL_TRANSITION', title: 'Conflict' },
                    409,
                )}
            />,
        );

        expect(screen.getByRole('alert').textContent).toBe('Conflict');
    });

    it('Given_a_problem_with_no_field_errors_When_rendered_Then_no_list_is_rendered', () => {
        render(
            <ErrorState
                error={apiError(
                    { status: 404, code: 'NOT_FOUND', detail: 'No such account.' },
                    404,
                )}
            />,
        );

        expect(screen.queryByRole('list')).toBeNull();
    });

    it('Given_a_problem_with_neither_detail_nor_title_When_rendered_Then_the_generic_sentence_is_shown', () => {
        render(<ErrorState error={apiError({ status: 500, code: 'INTERNAL' }, 500)} />);

        expect(screen.getByRole('alert').textContent).toBe(GENERIC_SENTENCE);
    });

    it('Given_an_ApiError_with_no_problem_When_rendered_Then_the_generic_sentence_is_shown', () => {
        render(<ErrorState error={apiError(undefined, 503)} />);

        expect(screen.getByRole('alert').textContent).toBe(GENERIC_SENTENCE);
    });

    it('Given_a_FORBIDDEN_problem_with_its_own_detail_When_rendered_Then_the_fixed_sentence_replaces_it', () => {
        // Neither the server's own detail nor a generic sentence: a viewer must never be told
        // which permission it was refused for.
        render(
            <ErrorState
                error={apiError(
                    {
                        status: 403,
                        code: 'FORBIDDEN',
                        title: 'Forbidden',
                        detail: 'Missing ledger:write',
                    },
                    403,
                )}
            />,
        );

        expect(screen.getByRole('alert').textContent).toBe(
            "You don't have permission to see this.",
        );
    });

    it('Given_a_plain_Error_When_rendered_Then_the_generic_sentence_is_shown_and_never_its_message_or_stack', () => {
        const error = new Error('Cannot read properties of undefined');

        render(<ErrorState error={error} />);

        const alert = screen.getByRole('alert');
        expect(alert.textContent).toBe(GENERIC_SENTENCE);
        expect(alert.textContent).not.toContain('Cannot read properties');
        expect(alert.textContent).not.toContain(' at ');
    });

    it('Given_a_thrown_value_that_is_not_an_Error_When_rendered_Then_the_generic_sentence_is_shown', () => {
        render(<ErrorState error="a string was thrown" />);

        expect(screen.getByRole('alert').textContent).toBe(GENERIC_SENTENCE);
    });

    it('Given_an_action_When_rendered_Then_it_is_offered_inside_the_alert', () => {
        render(<ErrorState error={apiError(undefined, 503)} action={<button>Try again</button>} />);

        const alert = screen.getByRole('alert');
        expect(within(alert).getByRole('button', { name: 'Try again' })).toBeDefined();
    });

    it('Given_no_action_When_rendered_Then_no_control_is_offered', () => {
        render(<ErrorState error={apiError(undefined, 503)} />);

        expect(screen.queryByRole('button')).toBeNull();
    });

    it('Given_a_ref_When_the_alert_is_focused_through_it_Then_the_alert_has_focus', () => {
        // A form moves focus here after a failed submit, so the failure is read and the next Tab
        // starts from it. A div takes programmatic focus only with a tabIndex.
        const ref = createRef<HTMLDivElement>();
        render(<ErrorState ref={ref} error={apiError(undefined, 503)} />);

        ref.current?.focus();

        expect(document.activeElement).toBe(screen.getByRole('alert'));
    });

    it('Given_an_error_state_When_rendered_Then_it_is_not_in_the_tab_order', () => {
        // Focusable for a script, never a Tab stop of its own.
        render(<ErrorState error={apiError(undefined, 503)} />);

        expect(screen.getByRole('alert').getAttribute('tabindex')).toBe('-1');
    });
});
