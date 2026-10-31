// @vitest-environment jsdom
// Renders through Testing Library and reads the session over a stubbed fetch, both of which
// need a DOM.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import { useWriteAccess } from '@/shared/lib/writeAccess';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { statusMessages } from '@/shared/testing/status';

import { SessionGate } from './SessionGate';
import { useSignedInSession } from './sessionContext';

const ME_URL = '/bff/me';

const CHILD_TEXT = 'protected content';

/** Reads the session `SessionGate` provided, proving it reached the tree rather than an error. */
function ProtectedChild() {
    const session = useSignedInSession();
    return <p>{`${CHILD_TEXT}: ${session.name}`}</p>;
}

/** Reads the write access `SessionGate` provided, proving it reached the tree too. */
function WriteAccessChild() {
    const { canWrite } = useWriteAccess();
    return <p>{`can write: ${String(canWrite)}`}</p>;
}

function renderGate(answer: FetchAnswer, child: ReactElement = <ProtectedChild />) {
    fakeFetch(answer);
    // retry: false - a 5xx test would otherwise wait out the app's real backoff.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
        <QueryClientProvider client={queryClient}>
            <SessionGate>{child}</SessionGate>
        </QueryClientProvider>,
    );
}

describe('SessionGate', () => {
    afterEach(() => {
        // A test that lands on a URL of its own, such as a failed sign-in redirect, must not
        // leave it for the next: SignIn reads the query string on every render.
        window.history.pushState({}, '', '/');
    });

    it('Given_the_session_is_still_loading_When_rendered_Then_a_status_announces_the_check', async () => {
        renderGate(() => pendingResponse());

        await waitFor(() => {
            expect(statusMessages()).toEqual(['Checking your session…']);
        });
    });

    it('Given_the_session_is_still_loading_When_rendered_Then_neither_the_sign_in_link_nor_the_children_show', () => {
        renderGate(() => pendingResponse());

        expect(screen.queryByRole('link', { name: /sign in/i })).toBeNull();
        expect(screen.queryByText(new RegExp(CHILD_TEXT))).toBeNull();
    });

    it('Given_a_signed_in_session_When_loaded_Then_the_children_render_with_that_session', async () => {
        renderGate((request) =>
            request.url === ME_URL
                ? jsonResponse({ subject: 'sub-1', name: 'Naim', roles: ['owner'] })
                : unexpectedRequest(request),
        );

        expect(await screen.findByText(`${CHILD_TEXT}: Naim`)).toBeDefined();
    });

    it('Given_a_signed_in_owner_When_loaded_Then_write_access_is_granted_to_the_tree', async () => {
        renderGate(
            (request) =>
                request.url === ME_URL
                    ? jsonResponse({ subject: 'sub-1', name: 'Naim', roles: ['owner'] })
                    : unexpectedRequest(request),
            <WriteAccessChild />,
        );

        expect(await screen.findByText('can write: true')).toBeDefined();
    });

    it('Given_a_signed_in_viewer_When_loaded_Then_write_access_is_refused_to_the_tree', async () => {
        renderGate(
            (request) =>
                request.url === ME_URL
                    ? jsonResponse({ subject: 'sub-2', name: 'A Viewer', roles: ['viewer'] })
                    : unexpectedRequest(request),
            <WriteAccessChild />,
        );

        expect(await screen.findByText('can write: false')).toBeDefined();
    });

    it('Given_nobody_is_signed_in_When_the_session_check_answers_401_Then_the_sign_in_link_shows', async () => {
        renderGate((request) =>
            request.url === ME_URL
                ? problemResponse({ status: 401, code: 'UNAUTHENTICATED' })
                : unexpectedRequest(request),
        );

        expect(await screen.findByRole('link', { name: /sign in/i })).toBeDefined();
        expect(screen.queryByText(new RegExp(CHILD_TEXT))).toBeNull();
    });

    it('Given_a_failed_sign_in_redirect_When_the_session_check_answers_401_Then_the_sign_in_screen_explains_it', async () => {
        window.history.pushState({}, '', '/?signin=failed');

        renderGate((request) =>
            request.url === ME_URL
                ? problemResponse({ status: 401, code: 'UNAUTHENTICATED' })
                : unexpectedRequest(request),
        );

        expect((await screen.findByRole('alert')).textContent).toBe('Sign-in failed. Try again.');
    });

    it('Given_the_session_check_fails_with_a_5xx_When_rendered_Then_a_retryable_error_shows_instead_of_the_sign_in_link', async () => {
        renderGate((request) =>
            request.url === ME_URL
                ? problemResponse({ status: 500, code: 'INTERNAL' })
                : unexpectedRequest(request),
        );

        expect(await screen.findByRole('alert')).toBeDefined();
        expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
        expect(screen.queryByRole('link', { name: /sign in/i })).toBeNull();
    });

    it('Given_the_session_check_fails_with_a_5xx_When_try_again_succeeds_Then_the_children_render', async () => {
        let attempt = 0;
        renderGate((request) => {
            if (request.url !== ME_URL) return unexpectedRequest(request);
            attempt += 1;
            return attempt === 1
                ? problemResponse({ status: 500, code: 'INTERNAL' })
                : jsonResponse({ subject: 'sub-1', name: 'Naim', roles: ['owner'] });
        });
        await screen.findByRole('alert');

        await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));

        await waitFor(() => {
            expect(screen.getByText(`${CHILD_TEXT}: Naim`)).toBeDefined();
        });
    });
});
