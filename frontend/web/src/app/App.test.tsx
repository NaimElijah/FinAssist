// @vitest-environment jsdom
// Renders the routed shell and clicks through it, so it needs a DOM. The router is an in-memory
// one over the real route table, so no test depends on the jsdom URL.

import { render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';

import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';

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

const ACCOUNTS_URL = '/ledger/api/v1/accounts';
const BUDGETS_URL = '/ledger/api/v1/budgets';
const PLANNED_FLOWS_URL = '/ledger/api/v1/planned-flows';
const HOLDINGS_URL = '/assistant/api/v1/portfolio/holdings';

const CHECKING = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    name: 'Checking',
    type: 'ASSET',
    currency: 'ILS',
} as const;

/**
 * A ledger holding no accounts, no budgets and no planned flows. The shell is under test here,
 * not the Accounts, Budgets or Planning screen, so each area's one list request is answered
 * locally and nothing reaches the network; any other request fails the test.
 */
const NO_ACCOUNTS: FetchAnswer = (request) => {
    if (request.url === ACCOUNTS_URL) return jsonResponse([]);
    if (request.url === BUDGETS_URL) return jsonResponse([]);
    if (request.url === PLANNED_FLOWS_URL) return jsonResponse([]);
    return unexpectedRequest(request);
};

/** A ledger holding Checking alone, with a zero balance and no transactions. */
const CHECKING_ONLY: FetchAnswer = (request) => {
    if (request.url === ACCOUNTS_URL) return jsonResponse([CHECKING]);
    if (request.url === `${ACCOUNTS_URL}/${CHECKING.id}/balance`) {
        return jsonResponse({ account: CHECKING.id, balance: { amount: '0.00', currency: 'ILS' } });
    }
    if (request.url === `${ACCOUNTS_URL}/${CHECKING.id}/transactions`) return jsonResponse([]);
    return unexpectedRequest(request);
};

const SEPTEMBER_BUDGET = {
    id: '5c0e8f7a-1b2d-4e3f-8a9b-0c1d2e3f4a5b',
    period: { start: '2026-09-01', end: '2026-10-01' },
    currency: 'ILS',
    limits: [],
    total: { amount: '0.00', currency: 'ILS' },
};

/** A ledger holding one budget for September 2026, with an empty review. */
const SEPTEMBER_BUDGET_ONLY: FetchAnswer = (request) => {
    if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
    if (request.url === `${BUDGETS_URL}/${SEPTEMBER_BUDGET.id}/review`) {
        return jsonResponse({
            budget: SEPTEMBER_BUDGET.id,
            period: SEPTEMBER_BUDGET.period,
            currency: 'ILS',
            categories: [],
            unbudgeted: [],
            uncategorised: { amount: '0.00', currency: 'ILS' },
        });
    }
    return unexpectedRequest(request);
};

/**
 * Render the provider composition main.tsx uses, over an in-memory router starting at `path`,
 * with the ledger faked by `answer`.
 */
function renderAt(path: string, answer: FetchAnswer = NO_ACCOUNTS) {
    fakeFetch(answer);
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

    it('Given_an_account_page_When_its_account_has_loaded_Then_the_document_title_names_the_account', async () => {
        renderAt(`/accounts/${CHECKING.id}`, CHECKING_ONLY);

        await screen.findByRole('heading', { level: 1, name: 'Checking' });
        await waitFor(() => {
            expect(document.title).toBe('Checking account · FinAssist');
        });
    });

    it('Given_an_account_page_When_its_account_has_not_loaded_Then_the_document_title_is_the_routes_own', async () => {
        // A page with nothing more specific to say sets no override, so the route's title stands.
        renderAt(`/accounts/${CHECKING.id}`, () => pendingResponse());

        await screen.findByText('Loading account…');
        expect(document.title).toBe('Account · FinAssist');
    });

    it('Given_an_account_page_When_Back_to_Accounts_is_clicked_Then_the_document_title_returns_to_the_area', async () => {
        // The override lasts only while the page that set it is mounted.
        renderAt(`/accounts/${CHECKING.id}`, CHECKING_ONLY);
        await screen.findByRole('heading', { level: 1, name: 'Checking' });

        await userEvent.setup().click(screen.getByRole('link', { name: 'Back to Accounts' }));

        await screen.findByRole('heading', { level: 1, name: 'Accounts' });
        expect(document.title).toBe('Accounts · FinAssist');
    });

    it('Given_a_budget_review_page_When_its_period_has_loaded_Then_the_document_title_names_the_review', async () => {
        renderAt(`/budgets/${SEPTEMBER_BUDGET.id}`, SEPTEMBER_BUDGET_ONLY);

        await screen.findByRole('heading', { level: 1, name: 'September 2026' });
        await waitFor(() => {
            expect(document.title).toBe('September 2026 review · FinAssist');
        });
    });

    it('Given_a_holding_page_When_rendered_Then_the_document_title_names_the_holding', async () => {
        // The symbol comes from the URL, so the title needs no figure to have loaded.
        renderAt('/portfolio/VOO', (request) =>
            request.url.startsWith(`${HOLDINGS_URL}/VOO/`)
                ? pendingResponse()
                : unexpectedRequest(request),
        );

        await screen.findByRole('heading', { level: 1, name: 'VOO' });
        await waitFor(() => {
            expect(document.title).toBe('VOO holding · FinAssist');
        });
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
