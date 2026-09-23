// @vitest-environment jsdom
// Renders through Testing Library, drives clicks through userEvent, and navigates the real
// (stubbed) window.location, all of which need a DOM.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';

import type { Session } from './sessionApi';
import { SessionContext } from './sessionContext';
import { UserMenu } from './UserMenu';

const LOGOUT_URL = '/bff/logout';
const LOGOUT_URL_RESPONSE = 'http://localhost:8081/realms/finassist/protocol/openid-connect/logout';

const OWNER: Session = { subject: 'sub-1', name: 'Naim', roles: ['owner'] };
const VIEWER: Session = { subject: 'sub-2', name: 'A Viewer', roles: ['viewer'] };

/** UserMenu fetches nothing on its own until Sign out is pressed. */
const NO_REQUESTS_EXPECTED: FetchAnswer = (request) => unexpectedRequest(request);

/** jsdom's real `Location`, restored after every test so no test leaks a stand-in to the next. */
const REAL_LOCATION = window.location;

/**
 * Replace `window.location` with a stand-in carrying only a mock `assign`: nothing under test
 * here reads any other member of `Location`, and spreading a class instance such as jsdom's
 * would lose its prototype.
 */
function spyOnAssign(): ReturnType<typeof vi.fn> {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
        value: { assign },
        writable: true,
        configurable: true,
    });
    return assign;
}

/** Renders `UserMenu` under the providers it needs: a query client and a resolved session. */
function renderUserMenu(session: Session, answer: FetchAnswer) {
    const requests = fakeFetch(answer);
    const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const rendered = render(
        <QueryClientProvider client={queryClient}>
            <SessionContext value={session}>
                <UserMenu />
            </SessionContext>
        </QueryClientProvider>,
    );
    return { ...rendered, queryClient, requests };
}

describe('UserMenu', () => {
    afterEach(() => {
        Object.defineProperty(window, 'location', {
            value: REAL_LOCATION,
            writable: true,
            configurable: true,
        });
    });

    it('Given_a_signed_in_owner_When_rendered_Then_the_owners_name_shows', () => {
        spyOnAssign();
        renderUserMenu(OWNER, NO_REQUESTS_EXPECTED);

        expect(screen.getByText('Naim')).toBeDefined();
    });

    it('Given_a_signed_in_owner_When_rendered_Then_no_read_only_badge_shows', () => {
        spyOnAssign();
        renderUserMenu(OWNER, NO_REQUESTS_EXPECTED);

        expect(screen.queryByText('Read-only')).toBeNull();
    });

    it('Given_a_signed_in_viewer_When_rendered_Then_a_read_only_badge_shows', () => {
        spyOnAssign();
        renderUserMenu(VIEWER, NO_REQUESTS_EXPECTED);

        expect(screen.getByText('Read-only')).toBeDefined();
    });

    it('Given_sign_out_is_clicked_When_it_succeeds_Then_the_csrf_header_is_sent_the_cache_is_cleared_and_the_browser_navigates_to_keycloaks_logout_url', async () => {
        document.cookie = 'FINASSIST-XSRF-TOKEN=csrf-token-value';
        const assign = spyOnAssign();
        const requests = fakeFetch((request) =>
            request.url === LOGOUT_URL
                ? jsonResponse({ logoutUrl: LOGOUT_URL_RESPONSE })
                : unexpectedRequest(request),
        );
        const queryClient = new QueryClient({
            defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
        });
        queryClient.setQueryData(['accounts'], ['something cached while signed in']);
        render(
            <QueryClientProvider client={queryClient}>
                <SessionContext value={OWNER}>
                    <UserMenu />
                </SessionContext>
            </QueryClientProvider>,
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));

        await waitFor(() => {
            expect(assign).toHaveBeenCalledExactlyOnceWith(LOGOUT_URL_RESPONSE);
        });
        expect(requests).toHaveLength(1);
        expect(new Headers(requests[0]?.headers).get('x-finassist-xsrf-token')).toBe(
            'csrf-token-value',
        );
        expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
    });

    it('Given_sign_out_is_pressed_twice_before_it_renders_again_When_it_settles_Then_only_one_request_was_sent', async () => {
        spyOnAssign();
        const { requests } = renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL ? pendingResponse() : unexpectedRequest(request),
        );
        const button = screen.getByRole('button', { name: 'Sign out' });

        // Two click events can arrive before the pending state has rendered, exactly like the
        // form double-submit case this mirrors (see AccountsPage.test.tsx).
        fireEvent.click(button);
        fireEvent.click(button);

        await waitFor(() => {
            expect(button).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_the_gateway_answers_with_a_non_http_logout_url_When_sign_out_succeeds_Then_the_browser_does_not_navigate', async () => {
        const assign = spyOnAssign();
        renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL
                ? jsonResponse({ logoutUrl: 'javascript:alert(1)' })
                : unexpectedRequest(request),
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));

        // Give the mutation's onSuccess a turn to run, then confirm it never reached
        // window.location.assign - browserLocation's own validation refused the value first.
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Sign out' })).toHaveProperty(
                'disabled',
                false,
            );
        });
        expect(assign).not.toHaveBeenCalled();
    });
});
