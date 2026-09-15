// @vitest-environment jsdom
// Renders the record-transaction screen through the feature's own routes, so it needs a DOM.
// fetch is faked, so the feature's real api/ module and its mapping run under test.

import { screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

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
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { accountingRoutes } from '../index';

const ACCOUNTS_URL = '/ledger/api/v1/accounts';
const RECORD_URL = '/ledger/api/v1/transactions';
const RECORD_TRANSACTION_PATH = '/accounts/transactions/new';

const CHECKING = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    name: 'Checking',
    type: 'ASSET',
    currency: 'ILS',
} as const;
const RENT = {
    id: '1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b',
    name: 'Rent',
    type: 'EXPENSE',
    currency: 'ILS',
} as const;

/** A ledger holding Checking and Rent, so both accounts can be picked in an entry row. */
function ledger(overrides?: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === ACCOUNTS_URL) return jsonResponse([CHECKING, RENT]);
        if (overrides !== undefined) return overrides(request);
        return unexpectedRequest(request);
    };
}

/** A ledger that also answers Checking's balance and transactions reads, for after navigation. */
function ledgerWithChecking(recordAnswer: FetchAnswer): FetchAnswer {
    return ledger((request) => {
        if (request.url === `${ACCOUNTS_URL}/${CHECKING.id}/balance`) {
            return jsonResponse({
                account: CHECKING.id,
                balance: { amount: '0.00', currency: 'ILS' },
            });
        }
        if (request.url === `${ACCOUNTS_URL}/${CHECKING.id}/transactions`) {
            return jsonResponse([]);
        }
        return recordAnswer(request);
    });
}

function renderForm() {
    return renderRoutes(accountingRoutes, RECORD_TRANSACTION_PATH);
}

/** Description, Checking as row 1, Rent as row 2, and a credit on row 1 that mirrors row 2's debit. */
async function fillValidTransaction(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText('Description'), 'Rent');
    await user.selectOptions(screen.getByLabelText('Account 1'), CHECKING.id);
    await user.selectOptions(screen.getByLabelText('Account 2'), RENT.id);
    await user.type(screen.getByLabelText('Credit 1'), '250.00');
}

const RECORDED_TRANSACTION = {
    id: 't1',
    occurredOn: '2026-09-01',
    description: 'Rent',
    entries: [
        { account: CHECKING.id, amount: { amount: '-250.00', currency: 'ILS' } },
        { account: RENT.id, amount: { amount: '250.00', currency: 'ILS' } },
    ],
    state: { status: 'PENDING' },
};

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('RecordTransactionPage routing', () => {
    it('Given_the_static_record_transaction_path_When_matched_Then_it_ranks_above_the_dynamic_account_route', async () => {
        fakeFetch(ledger());

        renderForm();

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Record a transaction' }),
        ).toBeDefined();
    });
});

describe('RecordTransactionPage entry rows', () => {
    it('Given_two_rows_When_a_debit_is_typed_on_row_1_Then_the_same_string_mirrors_into_row_2s_credit', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();

        await user.type(await screen.findByLabelText('Debit 1'), '250.00');

        expect(screen.getByLabelText('Credit 2')).toHaveProperty('value', '250.00');
    });

    it('Given_two_rows_When_a_credit_is_typed_on_row_1_Then_the_same_string_mirrors_into_row_2s_debit', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();

        await user.type(await screen.findByLabelText('Credit 1'), '90.00');

        expect(screen.getByLabelText('Debit 2')).toHaveProperty('value', '90.00');
    });

    it('Given_row_2_was_edited_directly_When_row_1_is_typed_into_again_Then_row_2_no_longer_mirrors', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await user.type(await screen.findByLabelText('Debit 1'), '100.00');
        expect(screen.getByLabelText('Credit 2')).toHaveProperty('value', '100.00');

        // Editing row 2 directly stops the mirror for good.
        await user.clear(screen.getByLabelText('Credit 2'));
        await user.type(screen.getByLabelText('Credit 2'), '999.00');
        await user.clear(screen.getByLabelText('Debit 1'));
        await user.type(screen.getByLabelText('Debit 1'), '500.00');

        expect(screen.getByLabelText('Credit 2')).toHaveProperty('value', '999.00');
    });

    it('Given_the_form_When_Add_entry_is_clicked_Then_a_third_rows_fields_appear', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await screen.findByLabelText('Account 1');

        await user.click(screen.getByRole('button', { name: 'Add entry' }));

        expect(screen.getByLabelText('Account 3')).toBeDefined();
        expect(screen.getByLabelText('Debit 3')).toBeDefined();
        expect(screen.getByLabelText('Credit 3')).toBeDefined();
    });

    it('Given_exactly_two_rows_When_rendered_Then_each_rows_remove_button_is_disabled', async () => {
        fakeFetch(ledger());
        renderForm();

        expect(await screen.findByRole('button', { name: 'Remove entry 1' })).toHaveProperty(
            'disabled',
            true,
        );
        expect(screen.getByRole('button', { name: 'Remove entry 2' })).toHaveProperty(
            'disabled',
            true,
        );
    });

    it('Given_a_third_row_When_added_Then_every_rows_remove_button_is_enabled', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await screen.findByLabelText('Account 1');

        await user.click(screen.getByRole('button', { name: 'Add entry' }));

        expect(screen.getByRole('button', { name: 'Remove entry 1' })).toHaveProperty(
            'disabled',
            false,
        );
        expect(screen.getByRole('button', { name: 'Remove entry 3' })).toHaveProperty(
            'disabled',
            false,
        );
    });
});

describe('RecordTransactionPage submission', () => {
    it('Given_a_filled_form_When_submitted_Then_it_posts_the_mapped_entries_with_each_accounts_currency', async () => {
        const today = todayIsoDate(new Date());
        const requests = fakeFetch(
            ledgerWithChecking((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return jsonResponse({ ...RECORDED_TRANSACTION, occurredOn: today }, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup();

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        await screen.findByRole('heading', { level: 1, name: 'Checking' });
        const posts = requests.filter((request) => request.method === 'POST');
        expect(posts).toEqual([
            {
                method: 'POST',
                url: RECORD_URL,
                body: {
                    occurredOn: today,
                    description: 'Rent',
                    entries: [
                        { account: CHECKING.id, amount: { amount: '-250.00', currency: 'ILS' } },
                        { account: RENT.id, amount: { amount: '250.00', currency: 'ILS' } },
                    ],
                },
            },
        ]);
    });

    it('Given_a_successful_recording_When_it_lands_Then_the_browser_moves_to_the_first_entrys_account', async () => {
        fakeFetch(
            ledgerWithChecking((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return jsonResponse(RECORDED_TRANSACTION, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup();

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'Checking' })).toBeDefined();
    });

    it('Given_a_422_RULE_VIOLATED_response_When_submitted_Then_the_server_detail_is_shown', async () => {
        fakeFetch(
            ledger((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 422,
                        code: 'RULE_VIOLATED',
                        detail: 'The entries do not sum to zero.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup();

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        const alert = await screen.findByRole('alert');
        expect(alert.textContent).toContain('The entries do not sum to zero.');
    });

    it('Given_a_request_in_flight_When_submitted_Then_the_submit_button_is_disabled', async () => {
        fakeFetch(
            ledger((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return pendingResponse();
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup();

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Record transaction' })).toHaveProperty(
                'disabled',
                true,
            );
        });
    });

    it('Given_a_row_with_both_a_debit_and_a_credit_When_submitted_Then_nothing_is_sent_and_the_row_shows_a_field_error', async () => {
        const requests = fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await user.type(await screen.findByLabelText('Description'), 'Rent');
        await user.selectOptions(screen.getByLabelText('Account 1'), CHECKING.id);
        await user.selectOptions(screen.getByLabelText('Account 2'), RENT.id);
        await user.type(screen.getByLabelText('Debit 1'), '100.00');
        // Row 2's credit was mirrored to '100.00'; editing it directly stops the mirror, so both
        // sides of row 2 can then be filled by hand.
        await user.clear(screen.getByLabelText('Credit 2'));
        await user.type(screen.getByLabelText('Credit 2'), '50.00');
        await user.type(screen.getByLabelText('Debit 2'), '50.00');

        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        // Both sides of row 2 carry the same message, since either one could be the fix.
        expect(await screen.findAllByText('Enter a debit or a credit')).toHaveLength(2);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    });
});
