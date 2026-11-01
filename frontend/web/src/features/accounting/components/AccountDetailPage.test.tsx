// @vitest-environment jsdom
// Renders an account's page through the feature's own routes, so it needs a DOM. fetch is faked,
// so the feature's real api/ module and its mapping run under test.

import { onlineManager, type QueryClient } from '@tanstack/react-query';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    networkFailure,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { changeValue, type FieldEdit } from '@/shared/testing/fieldEdits';
import { focusedElementHolds } from '@/shared/testing/focus';
import { pinClockJustAfterLocalMidnight } from '@/shared/testing/localClock';
import { renderRoutes } from '@/shared/testing/renderRoutes';
import { nameIsOrStartsWith, nameStartingWith } from '@/shared/testing/names';
import { statusMessages } from '@/shared/testing/status';

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

/**
 * The local date a lifecycle panel's Date field defaults to, written out rather than computed by
 * the code under test, so a call site reading the date some other way cannot agree with itself.
 */
const TODAY = '2026-09-15';

let restoreSink: () => void = () => undefined;
let restoreClock: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
    // Pins the clock just after local midnight in a zone where UTC is still on the day before,
    // so a default read through toISOString() would name 2026-09-14 and fail.
    restoreClock = pinClockJustAfterLocalMidnight(TODAY);
});

afterEach(() => {
    restoreSink();
    restoreClock();
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
     * Asserts a lifecycle action invalidated exactly the balance and transactions of the two
     * accounts its transaction names - Checking and Groceries - once each, and nothing wider:
     * a mutation invalidates only the keys that can have changed (frontend/web/CLAUDE.md).
     */
    function expectOnlyItsAccountsInvalidated(
        invalidateSpy: MockInstance<QueryClient['invalidateQueries']>,
    ): void {
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toHaveLength(4);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
                accountKeys.balance(GROCERIES_ID),
                accountKeys.transactions(GROCERIES_ID),
            ]),
        );
    }

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

        expect(within(row).getByRole('button', { name: nameStartingWith('Clear') })).toBeDefined();
        expect(within(row).getByRole('button', { name: nameStartingWith('Void') })).toBeDefined();
        expect(
            within(row).queryByRole('button', { name: nameStartingWith('Reconcile') }),
        ).toBeNull();
    });

    it('Given_a_pending_transaction_When_rendered_Then_each_action_is_named_by_its_word_then_the_row', async () => {
        // Out of the table, every row's "Clear" sounds alike; the row's description and date tell
        // them apart, after the visible word so a voice command naming it still matches (2.5.3).
        fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        const row = rowOf('Pending purchase');

        expect(
            within(row).getByRole('button', { name: 'Clear Pending purchase, 1 Sept 2026' }),
        ).toBeDefined();
        expect(
            within(row).getByRole('button', { name: 'Void Pending purchase, 1 Sept 2026' }),
        ).toBeDefined();
    });

    it('Given_a_cleared_transaction_When_Reconcile_is_opened_Then_the_panel_title_names_the_row', async () => {
        fakeFetch(lifecycleLedger(() => [CLEARED_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(
            within(rowOf('Cleared purchase')).getByRole('button', {
                name: 'Reconcile Cleared purchase, 20 Aug 2026',
            }),
        );

        expect(
            screen.getByRole('region', {
                name: 'Reconcile transaction: Cleared purchase, 20 Aug 2026',
            }),
        ).toBeDefined();
    });

    it('Given_a_cleared_transaction_When_rendered_Then_its_row_offers_Reconcile_and_Void_but_not_Clear', async () => {
        fakeFetch(lifecycleLedger(() => [CLEARED_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        const row = rowOf('Cleared purchase');

        expect(
            within(row).getByRole('button', { name: nameStartingWith('Reconcile') }),
        ).toBeDefined();
        expect(within(row).getByRole('button', { name: nameStartingWith('Void') })).toBeDefined();
        expect(within(row).queryByRole('button', { name: nameStartingWith('Clear') })).toBeNull();
    });

    it('Given_a_read_only_session_When_rendered_Then_no_row_offers_Clear_Reconcile_or_Void', async () => {
        fakeFetch(lifecycleLedger(() => [PENDING_TX, CLEARED_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`, {
            access: { canWrite: false },
        });

        await screen.findByRole('table', { name: 'Transactions' });

        expect(screen.queryByRole('button', { name: nameIsOrStartsWith('Clear') })).toBeNull();
        expect(screen.queryByRole('button', { name: nameIsOrStartsWith('Reconcile') })).toBeNull();
        expect(screen.queryByRole('button', { name: nameIsOrStartsWith('Void') })).toBeNull();
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

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
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
        expectOnlyItsAccountsInvalidated(invalidateSpy);
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
            within(rowOf('Cleared purchase')).getByRole('button', {
                name: nameStartingWith('Reconcile'),
            }),
        );
        await user.click(screen.getByRole('button', { name: 'Reconcile' }));

        expect(await within(rowOf('Cleared purchase')).findByText('Reconciled')).toBeDefined();
        expectOnlyItsAccountsInvalidated(invalidateSpy);
    });

    it('Given_a_pending_transaction_When_voided_without_a_reason_Then_the_required_field_refuses_submission_and_nothing_is_sent', async () => {
        // The Reason field's own `required` is the whole client-side check: the browser refuses
        // to fire the form's submit event while it is empty, so this is not a duplicate assertion
        // of custom validation logic - there is none - but of the native constraint itself.
        const requests = fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Void'),
            }),
        );
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

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Void'),
            }),
        );
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
        expectOnlyItsAccountsInvalidated(invalidateSpy);
    });

    it('Given_a_400_naming_the_panels_reason_When_voided_Then_the_error_state_lists_it_under_the_Reason_label', async () => {
        fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX],
                (request) => {
                    if (
                        request.url === `${RECORD_URL}/${PENDING_TX.id}/void` &&
                        request.method === 'POST'
                    ) {
                        return problemResponse({
                            status: 400,
                            code: 'MALFORMED_REQUEST',
                            detail: 'Invalid request content.',
                            errors: [{ field: 'reason', message: 'must not be blank' }],
                        });
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Void'),
            }),
        );
        await user.type(screen.getByLabelText('Reason'), ' ');
        await user.click(screen.getByRole('button', { name: 'Void' }));

        const alert = await screen.findByRole('alert');
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['Reason: must not be blank']);
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

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
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

    /**
     * A ledger whose Pending purchase was cleared elsewhere the moment this page tries to clear
     * it: the clear answers 409 `ILLEGAL_TRANSITION`, and every later read shows it cleared.
     */
    function clearedElsewhereLedger(): FetchAnswer {
        let transactions: readonly unknown[] = [PENDING_TX];
        return lifecycleLedger(
            () => transactions,
            (request) => {
                if (
                    request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                    request.method === 'POST'
                ) {
                    transactions = [
                        { ...PENDING_TX, state: { status: 'CLEARED', on: '2026-09-14' } },
                    ];
                    return problemResponse({
                        status: 409,
                        code: 'ILLEGAL_TRANSITION',
                        detail: 'The transaction is no longer pending.',
                    });
                }
                return unexpectedRequest(request);
            },
        );
    }

    it('Given_a_transaction_changed_elsewhere_When_its_action_answers_409_Then_this_accounts_rows_and_balance_are_refetched_beside_the_refusal', async () => {
        // Refetch on focus is off, so without this the row would keep offering an action the
        // ledger has already refused until the page is reloaded.
        fakeFetch(clearedElsewhereLedger());
        const { queryClient } = renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        expect(await within(rowOf('Pending purchase')).findByText('Cleared')).toBeDefined();
        expect(
            within(screen.getByRole('alert')).getByText('The transaction is no longer pending.'),
        ).toBeDefined();
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toHaveLength(2);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
            ]),
        );
    });

    it('Given_a_409_that_refetched_the_row_without_its_action_When_the_panel_is_cancelled_Then_focus_moves_to_the_rows_state', async () => {
        // The refetched row no longer offers Clear, so the button the panel would return focus to
        // is gone; the row's state, which is what changed, takes it instead of the page losing it.
        fakeFetch(clearedElsewhereLedger());
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        await user.click(screen.getByRole('button', { name: 'Clear' }));
        await within(rowOf('Pending purchase')).findByText('Cleared');

        await user.click(screen.getByRole('button', { name: 'Cancel' }));

        await waitFor(() => {
            expect(document.activeElement?.id).toBe(`transaction-state-${PENDING_TX.id}`);
        });
    });

    it('Given_a_refusal_other_than_409_When_an_action_is_submitted_Then_nothing_is_refetched', async () => {
        // Only a 409 says the row changed elsewhere; a 422 answers the request as sent, and a
        // mutation invalidates only the keys that can have changed. Counted at the fetch boundary,
        // so a refetch by any route - an invalidation or a direct refetch - is caught.
        const requests = fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX],
                (request) =>
                    request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                    request.method === 'POST'
                        ? problemResponse({
                              status: 422,
                              code: 'RULE_VIOLATED',
                              detail: 'A transaction cannot clear before it occurred.',
                          })
                        : unexpectedRequest(request),
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        const readsBefore = requests.filter((request) => request.method === 'GET').length;

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        await screen.findByRole('alert');
        expect(requests.filter((request) => request.method === 'GET')).toHaveLength(readsBefore);
    });

    // Every field of the panel, since each calls clearSettledOutcome on its own (frontend/web
    // CLAUDE.md: editing any field of a settled form clears its answer).
    for (const { field, edit } of [
        {
            field: 'reason',
            edit: (user) => user.type(screen.getByLabelText('Reason'), ' by mistake'),
        },
        {
            field: 'date',
            edit: () => changeValue('On', '2026-09-10'),
        },
    ] satisfies readonly FieldEdit[]) {
        it(`Given_a_refused_void_shown_in_its_panel_When_its_${field}_is_edited_Then_the_refusal_clears`, async () => {
            // The refusal answered the void as it was sent; once the owner changes it, the message
            // no longer describes anything in the panel.
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
            await user.click(
                within(rowOf('Pending purchase')).getByRole('button', {
                    name: nameStartingWith('Void'),
                }),
            );
            await user.type(screen.getByLabelText('Reason'), 'Entered twice');
            await user.click(screen.getByRole('button', { name: 'Void' }));
            await screen.findByRole('alert');

            await edit(user);

            expect(screen.queryByRole('alert')).toBeNull();
        });
    }

    it('Given_a_lifecycle_panel_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before pending has rendered. The panel's own
        // pending check reads render-time state, so only its in-flight ref stops the second, and a
        // repeated clear or void is permanent in an append-only journal (ADR-028).
        const requests = fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX],
                (request) =>
                    request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                    request.method === 'POST'
                        ? pendingResponse()
                        : unexpectedRequest(request),
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        const form = screen.getByLabelText('On').closest('form');
        if (form === null) {
            throw new Error('the On field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        // By the time pending has rendered, every request either submit started has reached fetch.
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Clear' })).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_a_clear_in_flight_When_another_rows_action_is_pressed_Then_it_is_disabled_and_the_clears_outcome_still_shows', async () => {
        // Opening another row's panel would unmount the one holding the mutation, and TanStack
        // Query runs a mutate call's callbacks only while its observer is mounted: a 504 "outcome
        // unknown" (ADR-067) would never be shown, and the owner would send the clear again.
        let answerClear: (response: Response) => void = () => undefined;
        fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX, CLEARED_TX],
                (request) =>
                    request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                    request.method === 'POST'
                        ? new Promise<Response>((resolve) => {
                              answerClear = resolve;
                          })
                        : unexpectedRequest(request),
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );

        await user.click(screen.getByRole('button', { name: 'Clear' }));

        const otherRow = rowOf('Cleared purchase');
        const reconcile = within(otherRow).getByRole('button', {
            name: nameStartingWith('Reconcile'),
        });
        await waitFor(() => {
            expect(reconcile).toHaveProperty('disabled', true);
        });
        expect(
            within(otherRow).getByRole('button', { name: nameStartingWith('Void') }),
        ).toHaveProperty('disabled', true);
        await user.click(reconcile);
        answerClear(
            problemResponse({
                status: 504,
                code: 'UPSTREAM_TIMEOUT',
                detail: 'The upstream service did not answer in time, so the outcome is unknown.',
            }),
        );

        const alert = await screen.findByRole('alert');
        expect(
            within(alert).getByText(
                'The upstream service did not answer in time, so the outcome is unknown.',
            ),
        ).toBeDefined();
        expect(
            screen.getByRole('region', { name: nameStartingWith('Clear transaction:') }),
        ).toBeDefined();
        expect(screen.queryByRole('region', { name: nameStartingWith('Reconcile') })).toBeNull();
        // Settled, the other row's actions are offered again.
        expect(reconcile).toHaveProperty('disabled', false);
    });

    it('Given_a_lifecycle_panel_just_submitted_When_another_rows_action_is_clicked_before_pending_renders_Then_the_panel_stays_open', async () => {
        // The click can land in the same tick as the submit, before the disabled buttons render,
        // so the page must refuse it from the mutation cache rather than from render-time state.
        fakeFetch(
            lifecycleLedger(
                () => [PENDING_TX, CLEARED_TX],
                (request) =>
                    request.url === `${RECORD_URL}/${PENDING_TX.id}/clear` &&
                    request.method === 'POST'
                        ? pendingResponse()
                        : unexpectedRequest(request),
            ),
        );
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        const form = screen.getByLabelText('On').closest('form');
        if (form === null) {
            throw new Error('the On field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.click(
            within(rowOf('Cleared purchase')).getByRole('button', {
                name: nameStartingWith('Reconcile'),
            }),
        );

        expect(screen.queryByRole('region', { name: nameStartingWith('Reconcile') })).toBeNull();
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Clear' })).toHaveProperty('disabled', true);
        });
    });

    // Every lifecycle mutation, since each sets its own network mode.
    for (const { action, transaction, reason } of [
        { action: 'Clear', transaction: PENDING_TX, reason: undefined },
        { action: 'Reconcile', transaction: CLEARED_TX, reason: undefined },
        { action: 'Void', transaction: PENDING_TX, reason: 'Entered twice' },
    ] as const) {
        it(`Given_the_browser_offline_When_a_${action}_is_submitted_Then_the_panel_shows_the_failure_and_Cancel_is_enabled_again`, async () => {
            // Offline, TanStack Query would hold the mutation paused and pending, and the panel
            // disables Cancel while pending: the owner would be stuck with nothing saying why.
            const actionUrl = `${RECORD_URL}/${transaction.id}/${action.toLowerCase()}`;
            const requests = fakeFetch(
                lifecycleLedger(
                    () => [transaction],
                    (request) =>
                        request.url === actionUrl && request.method === 'POST'
                            ? networkFailure()
                            : unexpectedRequest(request),
                ),
            );
            renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
            const user = userEvent.setup({ delay: null });
            await screen.findByRole('table', { name: 'Transactions' });
            await user.click(
                within(rowOf(transaction.description)).getByRole('button', {
                    name: nameStartingWith(action),
                }),
            );
            if (reason !== undefined) {
                await user.type(screen.getByLabelText('Reason'), reason);
            }
            try {
                act(() => {
                    onlineManager.setOnline(false);
                });

                await user.click(screen.getByRole('button', { name: action }));

                await screen.findByRole('alert');
                expect(screen.getByRole('button', { name: 'Cancel' })).toHaveProperty(
                    'disabled',
                    false,
                );
                expect(requests.filter((request) => request.url === actionUrl)).toHaveLength(1);
            } finally {
                onlineManager.setOnline(true);
            }
        });
    }

    it('Given_a_panel_open_on_a_pending_row_When_cancelled_Then_focus_returns_to_the_button_that_opened_it', async () => {
        fakeFetch(lifecycleLedger(() => [PENDING_TX], unexpectedRequest));
        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Transactions' });
        const clearButton = within(rowOf('Pending purchase')).getByRole('button', {
            name: nameStartingWith('Clear'),
        });

        await user.click(clearButton);
        await user.click(screen.getByRole('button', { name: 'Cancel' }));

        // Cancelling changes nothing about the transaction, so PENDING still offers Clear: the
        // same action is back, and focus returns to it.
        const restoredButton = within(rowOf('Pending purchase')).getByRole('button', {
            name: nameStartingWith('Clear'),
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

        await user.click(
            within(rowOf('Pending purchase')).getByRole('button', {
                name: nameStartingWith('Clear'),
            }),
        );
        await user.click(screen.getByRole('button', { name: 'Clear' }));

        const clearedText = await within(rowOf('Pending purchase')).findByText('Cleared');
        // The row no longer offers Clear - CLEARED offers Reconcile and Void - so focus falls
        // back to the row's own state cell rather than a button that no longer exists.
        await waitFor(() => {
            expect(document.activeElement?.id).toBe(`transaction-state-${PENDING_TX.id}`);
        });
        expect(
            within(rowOf('Pending purchase')).queryByRole('button', {
                name: nameIsOrStartsWith('Clear'),
            }),
        ).toBeNull();
        expect(clearedText).toBeDefined();
    });
});

describe('AccountDetailPage loading', () => {
    // Each note is a status region, so a screen reader hears that the part is loading rather
    // than meeting a blank where it will be (WCAG 4.1.3).
    it('Given_the_accounts_list_still_loading_When_the_page_renders_Then_a_status_says_the_account_is_loading', async () => {
        fakeFetch(() => pendingResponse());

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await waitFor(() => {
            expect(statusMessages()).toEqual(['Loading account…']);
        });
    });

    it('Given_the_balance_still_loading_When_the_page_renders_Then_a_status_says_the_balance_is_loading', async () => {
        fakeFetch(
            ledger(TRANSACTIONS, (request) =>
                request.url === TRANSACTIONS_URL ? jsonResponse(TRANSACTIONS) : pendingResponse(),
            ),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('table', { name: 'Transactions' });
        await waitFor(() => {
            expect(statusMessages()).toEqual(['Loading balance…']);
        });
    });

    it('Given_the_transactions_still_loading_When_the_page_renders_Then_a_status_says_they_are_loading', async () => {
        fakeFetch(
            ledger(TRANSACTIONS, (request) =>
                request.url === BALANCE_URL
                    ? jsonResponse({
                          account: CHECKING.id,
                          balance: { amount: '1234.50', currency: 'ILS' },
                      })
                    : pendingResponse(),
            ),
        );

        renderRoutes(accountingRoutes, `/accounts/${CHECKING.id}`);

        await screen.findByRole('heading', { level: 1, name: 'Checking' });
        await waitFor(() => {
            expect(statusMessages()).toEqual(['Loading transactions…']);
        });
    });
});
