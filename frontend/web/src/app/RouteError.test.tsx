// @vitest-environment jsdom
// Renders a data router whose route throws, so it needs a DOM.

import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/shared/api/client';
import { type LogRecord, setLogSink } from '@/shared/lib/logger';
import type { RouteHandle } from '@/shared/lib/routeHandle';

import { RouteError } from './RouteError';

const DETAIL = 'Account "Joint savings" is closed.';

function ExplodingWithError(): never {
    throw new Error('component blew up');
}

function ExplodingWithApiError(): never {
    throw new ApiError(409, '/ledger/api/v1/accounts/acc-1', DETAIL, {
        status: 409,
        code: 'ACCOUNT_CLOSED',
        detail: DETAIL,
    });
}

function Home() {
    return <p>home</p>;
}

/**
 * Render a one-route router whose component throws, with RouteError as its boundary, the route
 * declaring `handle` when one is given.
 */
function renderRoute(Component: () => never, handle?: RouteHandle) {
    const router = createMemoryRouter([
        { path: '/', Component, ErrorBoundary: RouteError, handle },
    ]);
    render(<RouterProvider router={router} />);
}

/**
 * Render a titled area's pathless boundary over a child route that throws, as routes.tsx mounts
 * every area: the title the screen would have had belongs to the matched child.
 */
function renderAreaRoute(Component: () => never, handle: RouteHandle) {
    const router = createMemoryRouter([
        { ErrorBoundary: RouteError, children: [{ path: '/', Component, handle }] },
    ]);
    render(<RouterProvider router={router} />);
}

/**
 * Render a one-route router at a path nothing matches. React Router raises its own 404 error
 * response for that, rather than an Error, and hands it to the root route's boundary.
 */
function renderUnmatchedPath() {
    const router = createMemoryRouter([{ path: '/', Component: Home, ErrorBoundary: RouteError }], {
        initialEntries: ['/nowhere'],
    });
    render(<RouterProvider router={router} />);
}

describe('RouteError', () => {
    let written: LogRecord[] = [];
    let restoreSink: () => void = () => undefined;

    beforeEach(() => {
        written = [];
        restoreSink = setLogSink((record) => written.push(record));
        // React and React Router both report a caught render error on the console themselves,
        // which is noise here and not what is under test.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);
        vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    });

    afterEach(() => {
        restoreSink();
        vi.restoreAllMocks();
    });

    it('Given_a_route_that_throws_an_Error_When_rendered_Then_the_generic_error_state_is_shown', async () => {
        renderRoute(ExplodingWithError);

        expect((await screen.findByRole('alert')).textContent).toBe(
            'Something went wrong. Try again, and reload the page if it keeps happening.',
        );
    });

    it('Given_a_titled_route_that_throws_When_rendered_Then_a_level_1_heading_names_the_route', async () => {
        // The layout moves focus to the page's h1 on navigation; a failed page needs one too, so
        // focus lands on a heading naming where the person is rather than on an unnamed main.
        renderAreaRoute(ExplodingWithError, { title: 'Budgets' });

        expect(await screen.findByRole('heading', { level: 1, name: 'Budgets' })).toBeDefined();
        expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    });

    it('Given_an_untitled_route_that_throws_When_rendered_Then_a_level_1_heading_says_the_page_failed', async () => {
        renderRoute(ExplodingWithError);

        expect(await screen.findByRole('heading', { level: 1, name: 'Page error' })).toBeDefined();
    });

    it('Given_a_route_that_throws_an_ApiError_When_rendered_Then_the_problem_detail_is_shown', async () => {
        renderRoute(ExplodingWithApiError);

        expect((await screen.findByRole('alert')).textContent).toBe(DETAIL);
    });

    it('Given_a_route_that_throws_an_Error_When_rendered_Then_the_failure_is_logged_once', async () => {
        renderRoute(ExplodingWithError);
        await screen.findByRole('alert');

        const failures = written.filter((record) => record.event === 'route failed');
        expect(failures).toHaveLength(1);
        expect(failures[0]).toMatchObject({ level: 'error', reason: 'component blew up' });
    });

    it('Given_a_path_no_route_matches_When_rendered_Then_the_route_error_response_is_logged_by_its_status', async () => {
        renderUnmatchedPath();
        await screen.findByRole('alert');

        const failures = written.filter((record) => record.event === 'route failed');
        expect(failures).toHaveLength(1);
        expect(failures[0]).toMatchObject({ level: 'error', status: 404 });
        expect(failures[0]).not.toHaveProperty('reason');
    });

    it('Given_a_route_that_throws_an_ApiError_When_logged_Then_the_record_carries_its_code_and_never_its_detail', async () => {
        // The detail can carry the owner's own words, such as an account name; ADR-019 redacts
        // at the boundary, and client.ts already refuses to log it for the same reason.
        renderRoute(ExplodingWithApiError);
        await screen.findByRole('alert');

        const failures = written.filter((record) => record.event === 'route failed');
        expect(failures).toHaveLength(1);
        expect(failures[0]).toMatchObject({ level: 'error', status: 409, code: 'ACCOUNT_CLOSED' });
        expect(JSON.stringify(failures[0])).not.toContain('Joint savings');
    });
});
