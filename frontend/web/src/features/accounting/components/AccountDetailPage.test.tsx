// @vitest-environment jsdom
// Renders an account's page through the feature's own routes, so it needs a DOM. fetch is faked,
// so the feature's real api/ module and its mapping run under test.

import { screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { accountingRoutes } from '../index';

const ACCOUNTS_URL = '/ledger/api/v1/accounts';

const CHECKING = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    name: 'Checking',
    type: 'ASSET',
    currency: 'ILS',
} as const;
const SALARY_ID = '7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d';
const RENT_ID = '1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b';
const GROCERIES_ID = '2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f';
const UNKNOWN_ID = '9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b';

const BALANCE_URL = `${ACCOUNTS_URL}/${CHECKING.id}/balance`;
const TRANSACTIONS_URL = `${ACCOUNTS_URL}/${CHECKING.id}/transactions`;

/** Three transactions touching Checking, in the order the ledger serves them. */
const TRANSACTIONS = [
    {
        id: 'b1',
        occurredOn: '2026-09-03',
        description: 'Rent',
        entries: [
            { account: CHECKING.id, amount: { amount: '-250.00', currency: 'ILS' } },
            { account: RENT_ID, amount: { amount: '250.00', currency: 'ILS' } },
        ],
        state: { status: 'VOIDED', on: '2026-09-04', reason: 'Entered twice' },
    },
    {
        id: 'a1',
        occurredOn: '2026-09-01',
        description: 'Salary',
        entries: [
            { account: SALARY_ID, amount: { amount: '-1000.00', currency: 'ILS' } },
            { account: CHECKING.id, amount: { amount: '1000.00', currency: 'ILS' } },
        ],
        state: { status: 'CLEARED', on: '2026-09-02' },
    },
    {
        id: 'c1',
        occurredOn: '2026-09-05',
        description: 'Groceries, paid in two',
        entries: [
            { account: CHECKING.id, amount: { amount: '-30.00', currency: 'ILS' } },
            { account: CHECKING.id, amount: { amount: '-20.00', currency: 'ILS' } },
            { account: GROCERIES_ID, amount: { amount: '50.00', currency: 'ILS' } },
        ],
        state: { status: 'PENDING' },
    },
] as const;

/** A ledger holding Checking, its balance and `transactions`; `overrides` answer first. */
function ledger(transactions: readonly unknown[], overrides?: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === ACCOUNTS_URL) return jsonResponse([CHECKING]);
        if (overrides !== undefined) return overrides(request);
        if (request.url === BALANCE_URL) {
            return jsonResponse({
                account: CHECKING.id,
                balance: { amount: '1234.50', currency: 'ILS' },
            });
        }
        if (request.url === TRANSACTIONS_URL) return jsonResponse(transactions);
        return unexpectedRequest(request);
    };
}

/** The table row a transaction's description heads. */
function rowOf(description: string): HTMLElement {
    const row = screen.getByRole('rowheader', { name: description }).closest('tr');
    if (row === null) {
        throw new Error(`no table row is headed by ${description}`);
    }
    return row;
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
    vi.unstubAllGlobals();
});

describe('AccountDetailPage', () => {
    it('Given_an_account_in_the_list_When_its_page_renders_Then_the_header_shows_it_and_its_balance_without_a_single_item_read', async () => {
        const requests = fakeFetch(ledger(TRANSACTIONS));

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        expect(await screen.findByRole('heading', { level: 1, name: 'Checking' })).toBeDefined();
        expect(screen.getByText('Asset · ILS')).toBeDefined();
        expect(await screen.findByText('₪1,234.50')).toBeDefined();
        // ADR-028: there are no single-item reads; the account comes from the list.
        expect(new Set(requests.map((request) => request.url))).toEqual(
            new Set([ACCOUNTS_URL, BALANCE_URL, TRANSACTIONS_URL]),
        );
        expect(screen.getByRole('link', { name: 'Back to Accounts' }).getAttribute('href')).toBe(
            '/accounts',
        );
    });

    it('Given_transactions_When_rendered_Then_rows_keep_server_order_and_show_only_this_accounts_amounts_as_received', async () => {
        fakeFetch(ledger(TRANSACTIONS));

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
            'Rent',
            'Salary',
            'Groceries, paid in two',
        ]);

        const rent = rowOf('Rent');
        expect(within(rent).getByText('-₪250.00')).toBeDefined();
        expect(within(rent).queryByText('₪250.00')).toBeNull();
        expect(rent.querySelector('time')?.getAttribute('datetime')).toBe('2026-09-03');

        const salary = rowOf('Salary');
        expect(within(salary).getByText('₪1,000.00')).toBeDefined();
        expect(within(salary).queryByText('-₪1,000.00')).toBeNull();

        // Two entries against this account are shown as two amounts, never summed into one.
        const groceries = rowOf('Groceries, paid in two');
        expect(within(groceries).getByText('-₪30.00')).toBeDefined();
        expect(within(groceries).getByText('-₪20.00')).toBeDefined();
        expect(within(groceries).queryByText('-₪50.00')).toBeNull();
        expect(within(groceries).queryByText('₪50.00')).toBeNull();
    });

    it('Given_a_voided_transaction_When_rendered_Then_its_badge_and_reason_are_shown_on_its_row_only', async () => {
        fakeFetch(ledger(TRANSACTIONS));

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        expect(within(rowOf('Rent')).getByText('Voided')).toBeDefined();
        expect(within(rowOf('Rent')).getByText('Entered twice')).toBeDefined();
        expect(within(rowOf('Salary')).getByText('Cleared')).toBeDefined();
        expect(within(rowOf('Salary')).queryByText('Entered twice')).toBeNull();
        expect(within(rowOf('Groceries, paid in two')).getByText('Pending')).toBeDefined();
    });

    it('Given_an_id_the_loaded_list_does_not_hold_When_rendered_Then_not_found_links_back_to_Accounts', async () => {
        // The per-account reads never answer, so the list alone decides.
        fakeFetch((request) =>
            request.url === ACCOUNTS_URL ? jsonResponse([CHECKING]) : pendingResponse(),
        );

        renderRoutes(accountingRoutes, `/accounts/${UNKNOWN_ID}`);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Account not found' }),
        ).toBeDefined();
        expect(screen.getByRole('link', { name: 'Back to Accounts' }).getAttribute('href')).toBe(
            '/accounts',
        );
    });

    it('Given_an_account_whose_balance_endpoint_answers_404_When_rendered_Then_not_found_is_shown', async () => {
        fakeFetch(
            ledger(TRANSACTIONS, (request) =>
                request.url === BALANCE_URL
                    ? problemResponse({ status: 404, code: 'ACCOUNT_NOT_FOUND' })
                    : pendingResponse(),
            ),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Account not found' }),
        ).toBeDefined();
        expect(screen.queryByRole('heading', { level: 1, name: 'Checking' })).toBeNull();
    });

    it('Given_no_transactions_touch_the_account_When_rendered_Then_the_empty_state_is_shown', async () => {
        fakeFetch(ledger([]));

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        expect(await screen.findByText('No transactions touch this account yet')).toBeDefined();
        expect(screen.queryByRole('table', { name: 'Transactions' })).toBeNull();
    });

    it('Given_the_transactions_fail_When_rendered_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch(
            ledger(TRANSACTIONS, (request) => {
                if (request.url === BALANCE_URL) {
                    return jsonResponse({
                        account: CHECKING.id,
                        balance: { amount: '0.00', currency: 'ILS' },
                    });
                }
                if (request.url !== TRANSACTIONS_URL) return unexpectedRequest(request);
                return failing
                    ? problemResponse({ status: 503, code: 'UNAVAILABLE', detail: 'Try later.' })
                    : jsonResponse([]);
            }),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Try later.')).toBeDefined();
        failing = false;
        await userEvent.setup().click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No transactions touch this account yet')).toBeDefined();
        expect(requests.filter((request) => request.url === TRANSACTIONS_URL)).toHaveLength(2);
    });

    it('Given_the_balance_fails_with_a_5xx_When_rendered_Then_an_error_state_offers_a_retry_and_the_account_is_still_shown', async () => {
        // Only ACCOUNT_NOT_FOUND means the account is missing; any other failure is a failure.
        fakeFetch(
            ledger(TRANSACTIONS, (request) => {
                if (request.url === BALANCE_URL) {
                    return problemResponse({
                        status: 500,
                        code: 'INTERNAL_ERROR',
                        detail: 'The ledger failed to handle this request.',
                    });
                }
                if (request.url === TRANSACTIONS_URL) return jsonResponse([]);
                return unexpectedRequest(request);
            }),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger failed to handle this request.')).toBeDefined();
        expect(within(alert).getByRole('button', { name: 'Try again' })).toBeDefined();
        expect(screen.getByRole('heading', { level: 1, name: 'Checking' })).toBeDefined();
        expect(screen.queryByRole('heading', { level: 1, name: 'Account not found' })).toBeNull();
    });
});
