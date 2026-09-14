// @vitest-environment jsdom
// Renders the routed shell and clicks through it, so it needs a DOM. The router is an in-memory
// one over the real route table, so no test depends on the jsdom URL.

import { render, screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';
import { createQueryClient } from './queryClient';
import { routes } from './routes';

/** The four areas, in the order the primary navigation lists them, with their paths. */
const AREAS = [
    { heading: 'Accounts', path: '/accounts' },
    { heading: 'Budgets', path: '/budgets' },
    { heading: 'Planning', path: '/planning' },
    { heading: 'Portfolio', path: '/portfolio' },
] as const;

/** The one request the shell's pages make: the Accounts area listing accounts. */
const ACCOUNTS_URL = '/ledger/api/v1/accounts';

beforeEach(() => {
    // The shell is under test here, not the Accounts screen, so its request is answered locally
    // with no accounts and nothing reaches the network. Anything else is refused loudly.
    vi.stubGlobal(
        'fetch',
        vi.fn<typeof fetch>().mockImplementation((input) =>
            input === ACCOUNTS_URL
                ? Promise.resolve(
                      new Response('[]', {
                          status: 200,
                          headers: { 'Content-Type': 'application/json' },
                      }),
                  )
                : Promise.reject(new Error('the shell test made a request it does not answer')),
        ),
    );
});

afterEach(() => {
    vi.unstubAllGlobals();
});

/** Render the provider composition main.tsx uses, over an in-memory router starting at `path`. */
function renderAt(path: string) {
    const router = createMemoryRouter(routes, { initialEntries: [path] });
    render(<App router={router} queryClient={createQueryClient()} />);
    return router;
}

describe('App', () => {
    it('Given_the_root_path_When_rendered_Then_it_lands_on_Accounts', async () => {
        const router = renderAt('/');

        expect(await screen.findByRole('heading', { level: 1, name: 'Accounts' })).toBeDefined();
        expect(router.state.location.pathname).toBe('/accounts');
    });

    it('Given_the_shell_When_rendered_Then_the_primary_nav_has_exactly_the_four_areas_in_order', async () => {
        renderAt('/accounts');

        const nav = await screen.findByRole('navigation', { name: 'Primary' });
        const links = within(nav).getAllByRole('link');
        expect(links.map((link) => link.textContent)).toEqual(AREAS.map((area) => area.heading));
        // The nav names its paths itself, so this pins them to the route contract the features
        // export; a feature changing its base path without the nav would fail here.
        expect(links.map((link) => link.getAttribute('href'))).toEqual(
            AREAS.map((area) => area.path),
        );
    });

    it('Given_an_area_is_open_When_rendered_Then_only_its_nav_link_is_marked_current', async () => {
        renderAt('/budgets');

        const nav = await screen.findByRole('navigation', { name: 'Primary' });
        expect(
            within(nav)
                .getAllByRole('link')
                .map((link) => link.getAttribute('aria-current')),
        ).toEqual([null, 'page', null, null]);
    });

    for (const { heading, path } of AREAS) {
        it(`Given_the_path_${path.slice(1)}_When_rendered_Then_its_heading_names_the_area`, async () => {
            renderAt(path);

            expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeDefined();
        });
    }

    it('Given_an_unknown_path_When_rendered_Then_the_not_found_page_is_shown', async () => {
        renderAt('/nope');

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
        ).toBeDefined();
        expect(screen.getByRole('navigation', { name: 'Primary' })).toBeDefined();
    });

    it('Given_the_Accounts_area_When_Budgets_is_clicked_Then_the_Budgets_heading_is_shown', async () => {
        renderAt('/accounts');
        const user = userEvent.setup();

        await user.click(await screen.findByRole('link', { name: 'Budgets' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'Budgets' })).toBeDefined();
        expect(screen.queryByRole('heading', { level: 1, name: 'Accounts' })).toBeNull();
    });

    it('Given_the_shell_When_rendered_Then_the_skip_link_targets_the_main_region', async () => {
        renderAt('/accounts');

        const skipLink = await screen.findByRole('link', { name: 'Skip to main content' });
        const main = screen.getByRole('main');
        expect(main.id).not.toBe('');
        expect(skipLink.getAttribute('href')).toBe(`#${main.id}`);
    });

    it('Given_an_area_is_open_When_rendered_Then_the_document_title_names_the_area_and_the_product', async () => {
        renderAt('/accounts');

        await screen.findByRole('heading', { level: 1, name: 'Accounts' });
        expect(document.title).toBe('Accounts · FinAssist');
    });

    it('Given_the_Accounts_area_When_Budgets_is_clicked_Then_the_document_title_follows_to_Budgets', async () => {
        renderAt('/accounts');

        await userEvent.setup().click(await screen.findByRole('link', { name: 'Budgets' }));

        await screen.findByRole('heading', { level: 1, name: 'Budgets' });
        expect(document.title).toBe('Budgets · FinAssist');
    });

    it('Given_an_unknown_path_When_rendered_Then_the_document_title_says_the_page_was_not_found', async () => {
        renderAt('/nope');

        await screen.findByRole('heading', { level: 1, name: 'Page not found' });
        expect(document.title).toBe('Page not found · FinAssist');
    });

    it('Given_the_root_path_When_the_shell_first_renders_Then_focus_stays_where_the_browser_put_it', async () => {
        // The redirect from / to /accounts is the app's own, not a person navigating, so a
        // keyboard user's first Tab must still reach the skip link rather than start inside main.
        renderAt('/');

        await screen.findByRole('heading', { level: 1, name: 'Accounts' });
        expect(document.activeElement).toBe(document.body);
    });

    it('Given_the_Accounts_area_When_a_nav_link_is_clicked_Then_focus_moves_to_the_main_region', async () => {
        renderAt('/accounts');

        await userEvent.setup().click(await screen.findByRole('link', { name: 'Budgets' }));

        await screen.findByRole('heading', { level: 1, name: 'Budgets' });
        expect(document.activeElement).toBe(screen.getByRole('main'));
    });
});
