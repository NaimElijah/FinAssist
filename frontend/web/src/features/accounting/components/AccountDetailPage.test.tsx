// @vitest-environment jsdom
// Renders an account's page through the feature's own routes, so it needs a DOM. fetch is faked,
// so the feature's real api/ module and its mapping run under test.

import { screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import { todayIsoDate } from '@/shared/lib/today';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { focusedElementHolds } from '@/shared/testing/focus';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { accountKeys } from '../hooks/accountQueries';
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

/** The pinned instant a lifecycle panel's Date field defaults from, and the ISO date it maps to. */
const FAKE_NOW = new Date(2026, 8, 15, 12, 0);
const TODAY = todayIsoDate(FAKE_NOW);

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
    // Pins Date so a run crossing local midnight cannot flake the default-to-today assertions.
    // Only Date is faked - user-event's own delays still run on real timers.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(FAKE_NOW);
});

afterEach(() => {
    restoreSink();
    vi.useRealTimers();
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

    it('Given_an_account_whose_transactions_endpoint_answers_404_When_rendered_Then_not_found_is_shown', async () => {
        // The balance never answers, so the transactions' 404 alone decides.
        fakeFetch(
            ledger(TRANSACTIONS, (request) =>
                request.url === TRANSACTIONS_URL
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
                    ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                    : jsonResponse([]);
            }),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Try later.')).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No transactions touch this account yet')).toBeDefined();
        expect(requests.filter((request) => request.url === TRANSACTIONS_URL)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on what the retry restored.
        await waitFor(() => {
            expect(
                focusedElementHolds(screen.getByText('No transactions touch this account yet')),
            ).toBe(true);
        });
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

    it('Given_the_balance_fails_with_a_5xx_When_Try_again_restores_it_Then_focus_moves_to_the_balance', async () => {
        let failing = true;
        fakeFetch(
            ledger(TRANSACTIONS, (request) => {
                if (request.url === BALANCE_URL) {
                    return failing
                        ? problemResponse({
                              status: 500,
                              code: 'INTERNAL_ERROR',
                              detail: 'The ledger failed to handle this request.',
                          })
                        : jsonResponse({
                              account: CHECKING.id,
                              balance: { amount: '1234.50', currency: 'ILS' },
                          });
                }
                if (request.url === TRANSACTIONS_URL) return jsonResponse([]);
                return unexpectedRequest(request);
            }),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const alert = await screen.findByRole('alert');

        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        const balance = await screen.findByText('₪1,234.50');
        await waitFor(() => {
            expect(focusedElementHolds(balance)).toBe(true);
        });
    });

    it('Given_the_accounts_list_fails_When_Try_again_restores_it_Then_focus_moves_to_the_accounts_heading', async () => {
        let failing = true;
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL) {
                return failing
                    ? problemResponse({
                          status: 502,
                          code: 'UNAVAILABLE',
                          detail: 'The ledger is restarting.',
                      })
                    : jsonResponse([CHECKING]);
            }
            if (request.url === BALANCE_URL) {
                return jsonResponse({
                    account: CHECKING.id,
                    balance: { amount: '1234.50', currency: 'ILS' },
                });
            }
            if (request.url === TRANSACTIONS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const alert = await screen.findByRole('alert');

        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        const heading = await screen.findByRole('heading', { level: 1, name: 'Checking' });
        await waitFor(() => {
            expect(document.activeElement).toBe(heading);
        });
    });
});

describe('AccountDetailPage transaction lifecycle actions', () => {
    const RECORD_URL = '/ledger/api/v1/transactions';

    const PENDING_TX = {
        id: 'p1',
        occurredOn: '2026-09-01',
        description: 'Pending purchase',
        entries: [
            { account: CHECKING.id, amount: { amount: '-40.00', currency: 'ILS' } },
            { account: GROCERIES_ID, amount: { amount: '40.00', currency: 'ILS' } },
        ],
        state: { status: 'PENDING' },
    } as const;
    const CLEARED_TX = {
        id: 'c1',
        occurredOn: '2026-08-20',
        description: 'Cleared purchase',
        entries: [
            { account: CHECKING.id, amount: { amount: '-20.00', currency: 'ILS' } },
            { account: GROCERIES_ID, amount: { amount: '20.00', currency: 'ILS' } },
        ],
        state: { status: 'CLEARED', on: '2026-08-21' },
    } as const;
    const RECONCILED_TX = {
        id: 'r1',
        occurredOn: '2026-08-10',
        description: 'Reconciled purchase',
        entries: [
            { account: CHECKING.id, amount: { amount: '-10.00', currency: 'ILS' } },
            { account: GROCERIES_ID, amount: { amount: '10.00', currency: 'ILS' } },
        ],
        state: { status: 'RECONCILED', on: '2026-08-11' },
    } as const;
    const VOIDED_TX = {
        id: 'v1',
        occurredOn: '2026-08-01',
        description: 'Voided purchase',
        entries: [
            { account: CHECKING.id, amount: { amount: '-5.00', currency: 'ILS' } },
            { account: GROCERIES_ID, amount: { amount: '5.00', currency: 'ILS' } },
        ],
        state: { status: 'VOIDED', on: '2026-08-02', reason: 'Entered twice' },
    } as const;

    /**
     * A ledger holding Checking, a fixed balance, and whichever transactions `getTransactions`
     * returns at the time of each read; `extra` answers everything else, such as a lifecycle POST.
     */
    function lifecycleLedger(
        getTransactions: () => readonly unknown[],
        extra: FetchAnswer,
    ): FetchAnswer {
        return (request) => {
            if (request.url === ACCOUNTS_URL) return jsonResponse([CHECKING]);
            if (request.url === BALANCE_URL) {
                return jsonResponse({
                    account: CHECKING.id,
                    balance: { amount: '0.00', currency: 'ILS' },
                });
            }
            if (request.url === TRANSACTIONS_URL && request.method === 'GET') {
                return jsonResponse(getTransactions());
            }
            return extra(request);
        };
    }

    it('Given_a_pending_transaction_When_rendered_Then_its_row_offers_Clear_and_Void_but_not_Reconcile', async () => {
        fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        const row = rowOf('Pending purchase');

        expect(within(row).getByRole('button', { name: 'Clear' })).toBeDefined();
        expect(within(row).getByRole('button', { name: 'Void' })).toBeDefined();
        expect(within(row).queryByRole('button', { name: 'Reconcile' })).toBeNull();
    });

    it('Given_a_cleared_transaction_When_rendered_Then_its_row_offers_Reconcile_and_Void_but_not_Clear', async () => {
        fakeFetch(lifecycleLedger(() => [CLEARED_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        const row = rowOf('Cleared purchase');

        expect(within(row).getByRole('button', { name: 'Reconcile' })).toBeDefined();
        expect(within(row).getByRole('button', { name: 'Void' })).toBeDefined();
        expect(within(row).queryByRole('button', { name: 'Clear' })).toBeNull();
    });

    it('Given_a_reconciled_or_a_voided_transaction_When_rendered_Then_neither_row_offers_any_action', async () => {
        fakeFetch(lifecycleLedger(() => [RECONCILED_TX, VOIDED_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });

        expect(within(rowOf('Reconciled purchase')).queryByRole('button')).toBeNull();
        expect(within(rowOf('Voided purchase')).queryByRole('button')).toBeNull();
    });

    it('Given_a_pending_transaction_When_cleared_Then_it_posts_on_and_the_row_shows_the_Cleared_badge', async () => {
        const today = TODAY;
        let transactions: readonly unknown[] = [PENDING_TX];
        const requests = fakeFetch(
            lifecycleLedger(
                () => transactions,
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                        request.method === 'POST'
                    ) {
                        const cleared = { ...PENDING_TX, state: { status: 'CLEARED', on: today } };
                        transactions = [cleared];
                        return jsonResponse(cleared);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Clear' }));
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        expect(await within(rowOf('Pending purchase')).findByText('Cleared')).toBeDefined();
        expect(
            requests
                .filter((request) => request.method === 'POST')
                .map(({ method, url, body }) => ({ method, url, body })),
        ).toEqual([
            { method: 'POST', url: `${RECORD_URL}/${PENDING_TX.id}/clear`, body: { on: today } },
        ]);
        // The balance is invalidated too, not only the transactions list the badge already proves.
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
            ]),
        );
    });

    it('Given_a_cleared_transaction_When_reconciled_Then_it_posts_on_and_the_row_shows_the_Reconciled_badge', async () => {
        const today = TODAY;
        let transactions: readonly unknown[] = [CLEARED_TX];
        fakeFetch(
            lifecycleLedger(
                () => transactions,
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${CLEARED_TX.id}/reconcile` &&
                        request.method === 'POST'
                    ) {
                        const reconciled = {
                            ...CLEARED_TX,
                            state: { status: 'RECONCILED', on: today },
                        };
                        transactions = [reconciled];
                        return jsonResponse(reconciled);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(
            within(rowOf('Cleared purchase')).getByRole('button', { name: 'Reconcile' }),
        );
        await user.click(screen.getByRole('button', { name: 'Reconcile' }));

        expect(await within(rowOf('Cleared purchase')).findByText('Reconciled')).toBeDefined();
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
            ]),
        );
    });

    it('Given_a_pending_transaction_When_voided_without_a_reason_Then_the_required_field_refuses_submission_and_nothing_is_sent', async () => {
        // The Reason field's own `required` is the whole client-side check: the browser refuses
        // to fire the form's submit event while it is empty, so this is not a duplicate assertion
        // of custom validation logic - there is none - but of the native constraint itself.
        const requests = fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Void' }));
        await user.click(screen.getByRole('button', { name: 'Void' }));

        const reasonField = screen.getByLabelText('Reason');
        expect(reasonField).toHaveProperty('validity.valueMissing', true);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
        // The panel stays open, unsubmitted.
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeDefined();
    });

    it('Given_a_pending_transaction_When_voided_with_a_reason_Then_it_posts_on_and_reason_and_shows_the_Voided_badge', async () => {
        const today = TODAY;
        let transactions: readonly unknown[] = [PENDING_TX];
        const requests = fakeFetch(
            lifecycleLedger(
                () => transactions,
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/void` &&
                        request.method === 'POST'
                    ) {
                        const voided = {
                            ...PENDING_TX,
                            state: { status: 'VOIDED', on: today, reason: 'Entered twice' },
                        };
                        transactions = [voided];
                        return jsonResponse(voided);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Void' }));
        await user.type(screen.getByLabelText('Reason'), 'Entered twice');
        await user.click(screen.getByRole('button', { name: 'Void' }));

        expect(await within(rowOf('Pending purchase')).findByText('Voided')).toBeDefined();
        expect(
            requests
                .filter((request) => request.method === 'POST')
                .map(({ method, url, body }) => ({ method, url, body })),
        ).toEqual([
            {
                method: 'POST',
                url: `${RECORD_URL}/${PENDING_TX.id}/void`,
                body: { on: today, reason: 'Entered twice' },
            },
        ]);
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
            ]),
        );
    });

    it('Given_a_409_response_When_an_action_is_submitted_Then_the_server_detail_is_shown_in_the_panel_and_it_stays_open', async () => {
        fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX],
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                        request.method === 'POST'
                    ) {
                        return problemResponse({
                            status: 409,
                            code: 'ILLEGAL_TRANSITION',
                            detail: 'The transaction is no longer pending.',
                        });
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Clear' }));
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The transaction is no longer pending.')).toBeDefined();
        // The panel stays open: its submit button, and the Date field, are still on screen.
        expect(screen.getByRole('button', { name: 'Clear' })).toBeDefined();
        expect(screen.getByLabelText('On')).toBeDefined();
        // On failure, focus moves to the alert - never to the state cell or the original button,
        // both of which belong to the success/cancel path.
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_a_refused_void_shown_in_its_panel_When_its_reason_is_edited_Then_the_refusal_clears', async () => {
        // The refusal answered the void as it was sent; once the owner changes it, the message no
        // longer describes anything in the panel.
        fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX],
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/void` &&
                        request.method === 'POST'
                    ) {
                        return problemResponse({
                            status: 422,
                            code: 'RULE_VIOLATED',
                            detail: 'A transaction cannot be voided before it occurred.',
                        });
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Void' }));
        await user.type(screen.getByLabelText('Reason'), 'Entered twice');
        await user.click(screen.getByRole('button', { name: 'Void' }));
        await screen.findByRole('alert');

        await user.type(screen.getByLabelText('Reason'), ' by mistake');

        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_panel_open_on_a_pending_row_When_cancelled_Then_focus_returns_to_the_button_that_opened_it', async () => {
        fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        const clearButton = within(rowOf('Pending purchase')).getByRole('button', {
            name: 'Clear',
        });

        await user.click(clearButton);
        await user.click(screen.getByRole('button', { name: 'Cancel' }));

        // Cancelling changes nothing about the transaction, so PENDING still offers Clear: the
        // same action is back, and focus returns to it.
        const restoredButton = within(rowOf('Pending purchase')).getByRole('button', {
            name: 'Clear',
        });
        expect(restoredButton).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(restoredButton);
        });
    });

    it('Given_a_panel_open_on_a_pending_row_When_cleared_successfully_Then_focus_moves_to_the_rows_new_state_since_Clear_is_gone', async () => {
        const today = TODAY;
        let transactions: readonly unknown[] = [PENDING_TX];
        fakeFetch(
            lifecycleLedger(
                () => transactions,
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                        request.method === 'POST'
                    ) {
                        const cleared = { ...PENDING_TX, state: { status: 'CLEARED', on: today } };
                        transactions = [cleared];
                        return jsonResponse(cleared);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(within(rowOf('Pending purchase')).getByRole('button', { name: 'Clear' }));
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        const clearedText = await within(rowOf('Pending purchase')).findByText('Cleared');
        // The row no longer offers Clear - CLEARED offers Reconcile and Void - so focus falls
        // back to the row's own state cell rather than a button that no longer exists.
        await waitFor(() => {
            expect(document.activeElement?.id).toBe(`transaction-state-${PENDING_TX.id}`);
        });
        expect(
            within(rowOf('Pending purchase')).queryByRole('button', { name: 'Clear' }),
        ).toBeNull();
        expect(clearedText).toBeDefined();
    });
});
