// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals. signOut does read document.cookie for the CSRF header, so
// stubDocumentCookie stands in for it below rather than pulling in jsdom for the whole file.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/shared/api/client';
import { stubDocumentCookie } from '@/shared/testing/stubDocumentCookie';

import { isReadOnly, readSession, type Session, signOut } from './sessionApi';

const ME_URL = '/bff/me';
const LOGOUT_URL = '/bff/logout';

/**
 * Install a stubbed fetch answering every call with a fresh response from `response`, since a
 * Response body can be read only once. Returns the spy, typed as the real fetch.
 */
function stubFetch(response: () => Response): ReturnType<typeof vi.fn<typeof fetch>> {
    const stub = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(response()));
    vi.stubGlobal('fetch', stub);
    return stub;
}

function jsonResponse(body: unknown, status = 200): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/json' },
        });
}

function problemResponse(body: unknown, status: number): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/problem+json' },
        });
}

/** The URL and init of the stub's only call, failing loudly when fetch was not called once. */
function onlyCall(stub: ReturnType<typeof stubFetch>): { url: string; init: RequestInit } {
    expect(stub).toHaveBeenCalledTimes(1);
    const [input, init] = stub.mock.calls[0] ?? [];
    if (typeof input !== 'string') {
        throw new Error('expected fetch to be called with a URL string');
    }
    return { url: input, init: init ?? {} };
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('readSession', () => {
    it('Given_a_session_response_When_read_Then_it_maps_onto_the_apps_own_session_type', async () => {
        const stub = stubFetch(
            jsonResponse({ subject: 'sub-123', name: 'Naim', roles: ['owner'] }),
        );

        const session = await readSession();

        expect(session).toEqual({ subject: 'sub-123', name: 'Naim', roles: ['owner'] });
        const { url } = onlyCall(stub);
        expect(url).toBe(ME_URL);
    });

    it('Given_nobody_signed_in_When_read_Then_a_401_UNAUTHENTICATED_is_thrown', async () => {
        stubFetch(problemResponse({ status: 401, code: 'UNAUTHENTICATED' }, 401));

        const failure: unknown = await readSession().catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).status).toBe(401);
        expect((failure as ApiError).problem?.code).toBe('UNAUTHENTICATED');
    });
});

describe('signOut', () => {
    it('Given_a_signed_in_session_When_signed_out_Then_the_csrf_header_is_sent_and_the_logout_url_is_returned', async () => {
        stubDocumentCookie('FINASSIST-XSRF-TOKEN=csrf-token-value');
        const stub = stubFetch(jsonResponse({ logoutUrl: 'http://localhost:8081/logout' }));

        const outcome = await signOut();

        expect(outcome).toEqual({ logoutUrl: 'http://localhost:8081/logout' });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(LOGOUT_URL);
        expect(init.method).toBe('POST');
        expect(new Headers(init.headers).get('X-FINASSIST-XSRF-TOKEN')).toBe('csrf-token-value');
    });
});

describe('isReadOnly', () => {
    const sessionWithRoles = (roles: readonly string[]): Session => ({
        subject: 'sub-123',
        name: 'Naim',
        roles,
    });

    it('Given_a_viewer_role_alone_When_checked_Then_it_is_read_only', () => {
        expect(isReadOnly(sessionWithRoles(['viewer']))).toBe(true);
    });

    it('Given_an_owner_role_alone_When_checked_Then_it_is_not_read_only', () => {
        expect(isReadOnly(sessionWithRoles(['owner']))).toBe(false);
    });

    it('Given_both_owner_and_viewer_roles_When_checked_Then_it_is_not_read_only', () => {
        // A token carrying both is an owner who also happens to have the viewer role, not a
        // viewer who happens to have the owner role too - the wider permission wins.
        expect(isReadOnly(sessionWithRoles(['owner', 'viewer']))).toBe(false);
    });

    it('Given_no_recognised_role_When_checked_Then_it_is_not_read_only', () => {
        // Neither role is present: nothing this screen shows is a write control yet (Task 16),
        // so there is nothing to badge as read-only.
        expect(isReadOnly(sessionWithRoles([]))).toBe(false);
    });
});
