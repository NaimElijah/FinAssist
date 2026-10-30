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
    problemResponse,
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

/** The CSRF cookie the gateway sets, which some tests plant in jsdom's cookie jar. */
const XSRF_COOKIE = 'FINASSIST-XSRF-TOKEN';

/** What the owner is told when signing out fails and the session is still open. */
const SIGN_OUT_FAILED =
    'Sign-out failed. You are still signed in. Try again, or close the browser.';

/** A query read while signed in, which a failed sign-out must leave in the cache. */
const CACHED_KEY = ['accounts'];
const CACHED_DATA = ['something cached while signed in'];

describe('UserMenu', () => {
    afterEach(() => {
        // An expired cookie is removed from the jar, so no later test sends a token it never set.
        document.cookie = `${XSRF_COOKIE}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
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
        document.cookie = `${XSRF_COOKIE}=csrf-token-value`;
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
        const { requests } = renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL
                ? jsonResponse({ logoutUrl: 'javascript:alert(1)' })
                : unexpectedRequest(request),
        );

        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));

        // The logout really was sent and answered: without it, a button that was never disabled
        // would satisfy the wait below, and the absence of navigation would prove nothing.
        await waitFor(() => {
            expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
                { method: 'POST', url: LOGOUT_URL },
            ]);
        });
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

    it('Given_sign_out_is_clicked_When_the_gateway_answers_403_csrf_token_invalid_Then_an_alert_says_the_session_is_still_open', async () => {
        document.cookie = `${XSRF_COOKIE}=csrf-token-value`;
        const assign = spyOnAssign();
        const { queryClient, requests } = renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL
                ? problemResponse({
                      status: 403,
                      code: 'CSRF_TOKEN_INVALID',
                      title: 'Forbidden',
                  })
                : unexpectedRequest(request),
        );
        queryClient.setQueryData(CACHED_KEY, CACHED_DATA);

        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));

        expect((await screen.findByRole('alert')).textContent).toBe(SIGN_OUT_FAILED);
        expect(requests.map(({ method, url }) => ({ method, url }))).toEqual([
            { method: 'POST', url: LOGOUT_URL },
        ]);
        // The session the cache was read under is still open, so its reads still stand, and the
        // owner can press Sign out again.
        expect(queryClient.getQueryData(CACHED_KEY)).toEqual(CACHED_DATA);
        expect(screen.getByRole('button', { name: 'Sign out' })).toHaveProperty('disabled', false);
        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_corrupted_csrf_cookie_When_sign_out_is_clicked_Then_an_alert_says_the_session_is_still_open', async () => {
        // Not valid percent-encoding, so `sendJson` throws a URIError before anything is sent.
        document.cookie = `${XSRF_COOKIE}=%E0%A4%A`;
        const assign = spyOnAssign();
        const { queryClient, requests } = renderUserMenu(OWNER, NO_REQUESTS_EXPECTED);
        queryClient.setQueryData(CACHED_KEY, CACHED_DATA);

        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));

        expect((await screen.findByRole('alert')).textContent).toBe(SIGN_OUT_FAILED);
        expect(requests).toHaveLength(0);
        expect(queryClient.getQueryData(CACHED_KEY)).toEqual(CACHED_DATA);
        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_failed_sign_out_When_it_is_retried_and_succeeds_Then_the_alert_is_gone_and_the_browser_navigates', async () => {
        const assign = spyOnAssign();
        let attempts = 0;
        renderUserMenu(OWNER, (request) => {
            if (request.url !== LOGOUT_URL) {
                return unexpectedRequest(request);
            }
            attempts += 1;
            return attempts === 1
                ? problemResponse({ status: 503, code: 'UPSTREAM_UNAVAILABLE' })
                : jsonResponse({ logoutUrl: LOGOUT_URL_RESPONSE });
        });
        const user = userEvent.setup();
        await user.click(screen.getByRole('button', { name: 'Sign out' }));
        await screen.findByRole('alert');

        await user.click(screen.getByRole('button', { name: 'Sign out' }));

        await waitFor(() => {
            expect(assign).toHaveBeenCalledExactlyOnceWith(LOGOUT_URL_RESPONSE);
        });
        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_sign_out_whose_pending_button_dropped_focus_When_it_fails_Then_focus_returns_to_sign_out', async () => {
        spyOnAssign();
        let answer: (response: Response) => void = () => undefined;
        renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL
                ? new Promise<Response>((resolve) => {
                      answer = resolve;
                  })
                : unexpectedRequest(request),
        );
        const button = screen.getByRole('button', { name: 'Sign out' });
        // A browser drops focus to the body once the pending button is disabled; jsdom keeps it
        // on the disabled button and will not blur one. A click that never focuses the button
        // leaves focus on the body, where the browser would have dropped it.
        fireEvent.click(button);
        await waitFor(() => {
            expect(button).toHaveProperty('disabled', true);
        });
        expect(document.activeElement).toBe(document.body);

        answer(problemResponse({ status: 503, code: 'UPSTREAM_UNAVAILABLE' }));

        await screen.findByRole('alert');
        await waitFor(() => {
            expect(document.activeElement).toBe(button);
        });
    });

    it('Given_focus_was_moved_elsewhere_during_sign_out_When_it_fails_Then_focus_stays_there', async () => {
        spyOnAssign();
        let answer: (response: Response) => void = () => undefined;
        renderUserMenu(OWNER, (request) =>
            request.url === LOGOUT_URL
                ? new Promise<Response>((resolve) => {
                      answer = resolve;
                  })
                : unexpectedRequest(request),
        );
        await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));
        const elsewhere = document.createElement('input');
        document.body.append(elsewhere);
        // Removed whatever the assertions do, so no later test inherits a stray focusable input.
        try {
            elsewhere.focus();

            answer(problemResponse({ status: 503, code: 'UPSTREAM_UNAVAILABLE' }));

            await screen.findByRole('alert');
            expect(document.activeElement).toBe(elsewhere);
        } finally {
            elsewhere.remove();
        }
    });

    it('Given_a_sign_out_that_failed_When_it_fails_again_Then_the_alert_is_mounted_afresh_so_it_is_announced_again', async () => {
        // The corrupted cookie fails each attempt before anything is sent, the quickest a second
        // failure can follow the first.
        document.cookie = `${XSRF_COOKIE}=%E0%A4%A`;
        spyOnAssign();
        renderUserMenu(OWNER, NO_REQUESTS_EXPECTED);
        const user = userEvent.setup();
        await user.click(screen.getByRole('button', { name: 'Sign out' }));
        const first = await screen.findByRole('alert');

        await user.click(screen.getByRole('button', { name: 'Sign out' }));

        await waitFor(() => {
            expect(screen.getByRole('alert')).not.toBe(first);
        });
        expect(first.isConnected).toBe(false);
    });
});
