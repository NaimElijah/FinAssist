// @vitest-environment jsdom
// Moves focus between routed pages, so it needs a DOM. The layout renders under the app's real
// composition - the session gate included - over a route table of its own, so each page here is
// shaped for the case it exercises rather than taken from a feature.

import { act, render, screen } from '@testing-library/react';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import type { RouteHandle } from '@/shared/lib/routeHandle';
import { fakeFetch, jsonResponse, unexpectedRequest } from '@/shared/testing/fakeFetch';

import { App } from './App';
import { Layout } from './Layout';
import { createQueryClient } from './queryClient';
import { RouteError } from './RouteError';

const ME_URL = '/bff/me';

const OWNER_SESSION = { subject: 'sub-owner', name: 'Naim', roles: ['owner'] };

/** The page every test starts on, so each navigation under test is a person's, not the first. */
function StartPage() {
    return <h1>Start</h1>;
}

/** A page with no h1 of its own. */
function HeadinglessPage() {
    return <p>Nothing to name here.</p>;
}

/** A page whose h1 makes itself focusable, as a retry's target does. */
function OwnTabIndexPage() {
    return <h1 tabIndex={0}>Own tab index</h1>;
}

function ExplodingPage(): never {
    throw new Error('page blew up');
}

/** The layout over the pages above, with one area boundary as routes.tsx mounts each area. */
const ROUTES: RouteObject[] = [
    {
        path: '/',
        Component: Layout,
        children: [
            { path: 'start', Component: StartPage },
            { path: 'headingless', Component: HeadinglessPage },
            { path: 'own-tab-index', Component: OwnTabIndexPage },
            {
                ErrorBoundary: RouteError,
                children: [
                    {
                        path: 'broken',
                        Component: ExplodingPage,
                        handle: { title: 'Broken page' } satisfies RouteHandle,
                    },
                ],
            },
        ],
    },
];

/** Render the layout on the start page, signed in, and return its router. */
async function renderOnStart() {
    fakeFetch((request) =>
        request.url === ME_URL ? jsonResponse(OWNER_SESSION) : unexpectedRequest(request),
    );
    const router = createMemoryRouter(ROUTES, { initialEntries: ['/start'] });
    render(<App router={router} queryClient={createQueryClient()} />);
    await screen.findByRole('heading', { level: 1, name: 'Start' });
    return router;
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // RouteError logs the failure it shows; one test throws on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
    vi.restoreAllMocks();
});

describe('Layout focus on navigation', () => {
    it('Given_a_page_with_no_h1_When_navigated_to_Then_focus_moves_to_main', async () => {
        // With nothing to name the page, main still takes focus, so the next Tab starts inside
        // the new page rather than on the link that was left behind.
        const router = await renderOnStart();

        await act(() => router.navigate('/headingless'));

        await screen.findByText('Nothing to name here.');
        expect(document.activeElement).toBe(screen.getByRole('main'));
    });

    it('Given_a_page_whose_h1_sets_its_own_tabindex_When_navigated_to_Then_it_takes_focus_and_keeps_that_tabindex', async () => {
        const router = await renderOnStart();

        await act(() => router.navigate('/own-tab-index'));

        const heading = await screen.findByRole('heading', { level: 1, name: 'Own tab index' });
        expect(document.activeElement).toBe(heading);
        expect(heading.getAttribute('tabindex')).toBe('0');
    });

    it('Given_a_page_that_throws_When_navigated_to_Then_focus_lands_on_the_error_pages_h1_naming_the_route', async () => {
        // React and React Router both report a caught render error on the console themselves.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);
        vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        const router = await renderOnStart();

        await act(() => router.navigate('/broken'));

        await screen.findByRole('alert');
        expect(document.activeElement).toBe(
            screen.getByRole('heading', { level: 1, name: 'Broken page' }),
        );
    });
});
