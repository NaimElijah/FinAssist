// @vitest-environment jsdom
// Renders the real route table, so what is under test is the translation src/app puts around the
// planning routes: accounting's accounts, offered on planning's projection screen. fetch is faked,
// so both features' real api/ modules run.

import { QueryClient } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { focusedElementHolds } from '@/shared/testing/focus';

import { App } from './App';
import { routes } from './routes';

const ACCOUNTS_URL = '/ledger/api/v1/accounts';
const SCENARIOS_URL = '/ledger/api/v1/scenarios';
const ME_URL = '/bff/me';

/**
 * Answer the session check as a signed-in owner, and delegate everything else to `answer`.
 *
 * This file renders the real `App` and route table, which now sit behind `SessionGate`
 * (ADR-030): without this, the first request any test here makes would be answered 401 and the
 * projection page under test would never render at all.
 */
function withOwnerSession(answer: FetchAnswer): FetchAnswer {
    return (request) =>
        request.url === ME_URL
            ? jsonResponse({ subject: 'sub-owner', name: 'Naim', roles: ['owner'] })
            : answer(request);
}

/** Four ILS accounts and one USD account, in the order the ledger lists them. */
const ACCOUNTS = [
    {
        id: 'a1a1a1a1-0000-4000-8000-000000000001',
        name: 'Checking',
        type: 'ASSET',
        currency: 'ILS',
    },
    { id: 'a1a1a1a1-0000-4000-8000-000000000002', name: 'Wallet', type: 'ASSET', currency: 'ILS' },
    {
        id: 'a1a1a1a1-0000-4000-8000-000000000003',
        name: 'Brokerage',
        type: 'ASSET',
        currency: 'ILS',
    },
    { id: 'a1a1a1a1-0000-4000-8000-000000000004', name: 'Pension', type: 'ASSET', currency: 'ILS' },
    {
        id: 'a1a1a1a1-0000-4000-8000-000000000005',
        name: 'US checking',
        type: 'ASSET',
        currency: 'USD',
    },
] as const;

const [CHECKING, WALLET, BROKERAGE, PENSION] = ACCOUNTS;

const SAVING = {
    id: 'c1c1c1c1-0000-4000-8000-000000000001',
    name: 'Aggressive saving',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0.005',
} as const;

const ABROAD = {
    id: 'c1c1c1c1-0000-4000-8000-000000000002',
    name: 'Move abroad',
    currency: 'USD',
    flows: [],
    growthRatePerStep: '0',
} as const;

/** `SAVING` projected over two monthly steps, never below zero. */
const PROJECTION = {
    scenario: SAVING.id,
    horizon: { from: '2026-09-01', to: '2026-11-01' },
    step: 'MONTHLY',
    currency: 'ILS',
    points: ['2026-09-01', '2026-10-01', '2026-11-01'].map((date) => ({
        date,
        cash: { amount: '100.00', currency: 'ILS' },
        invested: { amount: '200.00', currency: 'ILS' },
        total: { amount: '300.00', currency: 'ILS' },
    })),
};

const PROJECTION_PREFIX = `${SCENARIOS_URL}/${SAVING.id}/projection?`;

/**
 * A ledger holding the two scenarios, answering the accounts list through `accounts` and a
 * projection of `SAVING` with `PROJECTION`.
 */
function ledger(accounts: () => Response | Promise<Response>): FetchAnswer {
    return (request) => {
        if (request.url === ACCOUNTS_URL) return accounts();
        if (request.url === SCENARIOS_URL) return jsonResponse([SAVING, ABROAD]);
        if (request.url.startsWith(PROJECTION_PREFIX)) return jsonResponse(PROJECTION);
        return unexpectedRequest(request);
    };
}

/**
 * Render the app at the projection page over the real route table, with the ledger faked by
 * `answer`. The client retries nothing, so a failure shows at once rather than after a backoff.
 */
function renderProjectionPage(answer: FetchAnswer) {
    const requests = fakeFetch(withOwnerSession(answer));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter(routes, { initialEntries: ['/planning/projection'] });
    render(<App router={router} queryClient={queryClient} />);
    return { requests, queryClient };
}

/** The labels of the checkboxes in the account group named `group`. */
function accountLabels(group: string): (string | null | undefined)[] {
    return within(screen.getByRole('group', { name: group }))
        .getAllByRole('checkbox')
        .map((checkbox) => checkbox.closest('label')?.textContent);
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; one test fails the accounts read on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('PlanningAccountsBridge', () => {
    it('Given_accounts_in_two_currencies_When_a_scenario_is_chosen_Then_the_projection_page_offers_accountings_accounts_in_its_currency', async () => {
        renderProjectionPage(ledger(() => jsonResponse(ACCOUNTS)));
        const user = userEvent.setup({ delay: null });

        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );

        expect(accountLabels('Cash accounts')).toEqual([
            'Checking',
            'Wallet',
            'Brokerage',
            'Pension',
        ]);
        expect(accountLabels('Invested accounts')).toEqual([
            'Checking',
            'Wallet',
            'Brokerage',
            'Pension',
        ]);

        await user.selectOptions(screen.getByLabelText('Scenario'), 'Move abroad (USD)');

        expect(accountLabels('Cash accounts')).toEqual(['US checking']);
    });

    it('Given_accounts_chosen_When_projected_Then_the_request_repeats_cash_and_invested_with_each_accounts_id', async () => {
        const { requests } = renderProjectionPage(ledger(() => jsonResponse(ACCOUNTS)));
        const user = userEvent.setup({ delay: null });
        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );
        const cash = within(screen.getByRole('group', { name: 'Cash accounts' }));
        const invested = within(screen.getByRole('group', { name: 'Invested accounts' }));

        await user.click(cash.getByLabelText('Checking'));
        await user.click(cash.getByLabelText('Wallet'));
        await user.click(invested.getByLabelText('Brokerage'));
        await user.click(invested.getByLabelText('Pension'));
        fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-01' } });
        fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-01' } });
        await user.click(screen.getByRole('button', { name: 'Project' }));

        await screen.findByRole('table', { name: 'Projection' });
        expect(
            requests
                .filter((request) => request.url.startsWith(PROJECTION_PREFIX))
                .map((request) => request.url),
        ).toEqual([
            `${PROJECTION_PREFIX}from=2026-09-01&to=2026-11-01&step=MONTHLY` +
                `&cash=${CHECKING.id}&cash=${WALLET.id}` +
                `&invested=${BROKERAGE.id}&invested=${PENSION.id}`,
        ]);
    });

    it('Given_the_accounts_still_loading_When_the_projection_page_renders_Then_its_pickers_say_so', async () => {
        renderProjectionPage(ledger(() => pendingResponse()));

        expect(await screen.findByText('Loading accounts…')).toBeDefined();
    });

    it('Given_the_accounts_read_fails_When_the_projection_page_renders_Then_only_its_pickers_fail_and_a_retry_reads_them_again', async () => {
        let failing = true;
        const { requests } = renderProjectionPage(
            ledger(() =>
                failing
                    ? problemResponse({
                          status: 502,
                          code: 'UNAVAILABLE',
                          detail: 'Accounts are unavailable.',
                      })
                    : jsonResponse(ACCOUNTS),
            ),
        );
        const user = userEvent.setup({ delay: null });

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Accounts are unavailable.')).toBeDefined();
        expect(screen.getByLabelText('Scenario')).toBeDefined();

        failing = false;
        await user.click(within(alert).getByRole('button', { name: 'Try again' }));
        // The retry button went with the failure, so focus lands on the pickers it restored.
        await waitFor(() => {
            expect(
                focusedElementHolds(
                    screen.getByText('Choose a scenario to offer the accounts in its currency.'),
                ),
            ).toBe(true);
        });
        await user.selectOptions(screen.getByLabelText('Scenario'), 'Aggressive saving (ILS)');

        expect(await screen.findAllByRole('checkbox')).toHaveLength(8);
        expect(requests.filter((request) => request.url === ACCOUNTS_URL)).toHaveLength(2);
    });

    it('Given_accounts_on_screen_When_a_later_read_of_them_fails_Then_the_checkboxes_stay_beside_the_failure', async () => {
        let accountReads = 0;
        const { queryClient } = renderProjectionPage(
            ledger(() => {
                accountReads += 1;
                return accountReads === 1
                    ? jsonResponse(ACCOUNTS)
                    : problemResponse({
                          status: 503,
                          code: 'UNAVAILABLE',
                          detail: 'Accounts are unavailable.',
                      });
            }),
        );
        const user = userEvent.setup({ delay: null });
        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );
        await screen.findAllByRole('checkbox');

        await act(() => queryClient.refetchQueries());

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Accounts are unavailable.')).toBeDefined();
        await waitFor(() => {
            expect(accountLabels('Cash accounts')).toEqual([
                'Checking',
                'Wallet',
                'Brokerage',
                'Pension',
            ]);
        });
    });
});
