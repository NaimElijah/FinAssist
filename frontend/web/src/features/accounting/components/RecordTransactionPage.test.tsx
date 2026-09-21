// @vitest-environment jsdom
// Renders the record-transaction screen through the feature's own routes, so it needs a DOM.
// fetch is faked, so the feature's real api/ module and its mapping run under test.

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
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
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { accountKeys } from '../hooks/accountQueries';
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
// A different currency from Checking's, on purpose: it proves each entry's currency comes from
// its own posted-to account, not from the first row or a hardcoded default.
const RENT = {
    id: '1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b',
    name: 'Rent',
    type: 'EXPENSE',
    currency: 'USD',
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

/**
 * Description, Checking as row 1, Rent as row 2, and a credit on row 1 that mirrors row 2's debit.
 */
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
        { account: RENT.id, amount: { amount: '250.00', currency: 'USD' } },
    ],
    state: { status: 'PENDING' },
};

/** The pinned instant the form's Date field defaults from, and the ISO date it maps to. */
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

describe('RecordTransactionPage routing', () => {
    it('Given_the_two_segment_record_transaction_path_When_matched_Then_it_does_not_collide_with_the_single_segment_account_route', async () => {
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
        const user = userEvent.setup({ delay: null });
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
        const user = userEvent.setup({ delay: null });
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

    it('Given_a_row_added_then_a_different_one_removed_When_row_1_is_typed_into_again_Then_the_remaining_rows_typed_amount_survives', async () => {
        // Repro: Add entry -> type Credit 3 = 70 -> Remove entry 2 -> type Debit 1. Before the
        // fix, the mirror wrote to whatever sat at position 1 after the removal (formerly row 3),
        // clobbering its typed '70.00'.
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await screen.findByLabelText('Account 1');
        await user.click(screen.getByRole('button', { name: 'Add entry' }));

        await user.type(screen.getByLabelText('Credit 3'), '70.00');
        await user.click(screen.getByRole('button', { name: 'Remove entry 2' }));
        await user.type(screen.getByLabelText('Debit 1'), '10.00');

        expect(screen.getByLabelText('Credit 2')).toHaveProperty('value', '70.00');
    });

    it('Given_a_row_was_added_and_then_removed_back_to_two_When_row_1_is_typed_into_Then_the_mirror_does_not_resume', async () => {
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup();
        await screen.findByLabelText('Account 1');
        await user.click(screen.getByRole('button', { name: 'Add entry' }));

        await user.click(screen.getByRole('button', { name: 'Remove entry 3' }));
        await user.type(screen.getByLabelText('Debit 1'), '10.00');

        expect(screen.getByLabelText('Credit 2')).toHaveProperty('value', '');
    });
});

describe('RecordTransactionPage submission', () => {
    it('Given_a_filled_form_When_submitted_Then_it_posts_the_mapped_entries_with_each_accounts_own_currency', async () => {
        const requests = fakeFetch(
            ledgerWithChecking((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return jsonResponse({ ...RECORDED_TRANSACTION, occurredOn: TODAY }, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        await screen.findByRole('heading', { level: 1, name: 'Checking' });
        const posts = requests
            .filter((request) => request.method === 'POST')
            // Projected before comparing: RecordedRequest also carries headers, which this
            // assertion is not about, and toEqual demands an exact match on every field it is
            // given - unlike toMatchObject, which would stop checking body for an unplanned
            // extra field anywhere in its object graph.
            .map(({ method, url, body }) => ({ method, url, body }));
        expect(posts).toEqual([
            {
                method: 'POST',
                url: RECORD_URL,
                body: {
                    occurredOn: TODAY,
                    description: 'Rent',
                    entries: [
                        // Checking is ILS, Rent is USD: each entry carries its own account's
                        // currency, not the first row's or a hardcoded default.
                        { account: CHECKING.id, amount: { amount: '-250.00', currency: 'ILS' } },
                        { account: RENT.id, amount: { amount: '250.00', currency: 'USD' } },
                    ],
                },
            },
        ]);
    });

    it('Given_two_accounts_named_in_the_entries_When_recorded_Then_only_their_balance_and_transactions_keys_are_invalidated', async () => {
        // The accounts list carries id, name, type and currency, none of which a transaction can
        // change, so it must not be invalidated - only balance(id) and transactions(id) for the
        // accounts the entries actually name.
        fakeFetch(
            ledgerWithChecking((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return jsonResponse(RECORDED_TRANSACTION, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        const { queryClient } = renderForm();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));
        await screen.findByRole('heading', { level: 1, name: 'Checking' });

        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([
                accountKeys.balance(CHECKING.id),
                accountKeys.transactions(CHECKING.id),
                accountKeys.balance(RENT.id),
                accountKeys.transactions(RENT.id),
            ]),
        );
        expect(invalidatedKeys).not.toContainEqual(accountKeys.list);
        expect(invalidatedKeys).toHaveLength(4);
    });

    it('Given_one_account_named_in_two_entries_When_recorded_Then_its_keys_are_invalidated_only_once', async () => {
        const transactionNamingCheckingTwice = {
            ...RECORDED_TRANSACTION,
            entries: [
                { account: CHECKING.id, amount: { amount: '-100.00', currency: 'ILS' } },
                { account: RENT.id, amount: { amount: '150.00', currency: 'USD' } },
                { account: CHECKING.id, amount: { amount: '-50.00', currency: 'ILS' } },
            ],
        };
        fakeFetch(
            ledgerWithChecking((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return jsonResponse(transactionNamingCheckingTwice, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        const { queryClient } = renderForm();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));
        await screen.findByRole('heading', { level: 1, name: 'Checking' });

        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        const matching = (target: unknown) =>
            invalidatedKeys.filter((key) => JSON.stringify(key) === JSON.stringify(target));
        expect(matching(accountKeys.balance(CHECKING.id))).toHaveLength(1);
        expect(matching(accountKeys.transactions(CHECKING.id))).toHaveLength(1);
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
        const user = userEvent.setup({ delay: null });

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
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        const alert = await screen.findByRole('alert');
        expect(alert.textContent).toContain('The entries do not sum to zero.');
    });

    it('Given_a_422_refusal_shown_When_an_amount_is_edited_Then_the_refusal_clears', async () => {
        // The refusal described the entries that were sent; kept on screen, it would contradict
        // the entries the owner has since corrected.
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
        const user = userEvent.setup({ delay: null });
        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));
        await screen.findByRole('alert');

        await user.type(screen.getByLabelText('Debit 2'), '0');

        expect(screen.queryByRole('alert')).toBeNull();
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
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Record transaction' })).toHaveProperty(
                'disabled',
                true,
            );
        });
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard reading render-time state lets both through, and a duplicated transaction can only
        // be voided (ADR-028).
        const requests = fakeFetch(
            ledger((request) =>
                request.url === RECORD_URL && request.method === 'POST'
                    ? pendingResponse()
                    : unexpectedRequest(request),
            ),
        );
        renderForm();
        await fillValidTransaction(userEvent.setup({ delay: null }));
        const form = screen.getByLabelText('Description').closest('form');
        if (form === null) {
            throw new Error('the Description field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        // By the time pending has rendered, every request either submit started has reached fetch.
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Record transaction' })).toHaveProperty(
                'disabled',
                true,
            );
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_a_row_with_both_a_debit_and_a_credit_When_submitted_Then_nothing_is_sent_and_the_row_shows_a_field_error', async () => {
        const requests = fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup({ delay: null });
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

    it('Given_a_row_removed_from_the_middle_When_another_rows_error_is_shown_Then_it_stays_on_its_own_row', async () => {
        // Reproduces the bug a position-keyed error would have: removing row 1 shifts row 2 into
        // row 1's slot, which must not carry row 2's error along with it if errors are keyed by
        // the row's stable id rather than by array position.
        fakeFetch(ledger());
        renderForm();
        const user = userEvent.setup({ delay: null });
        await user.type(await screen.findByLabelText('Description'), 'Rent');
        await user.click(screen.getByRole('button', { name: 'Add entry' }));
        await user.selectOptions(screen.getByLabelText('Account 1'), CHECKING.id);
        await user.selectOptions(screen.getByLabelText('Account 2'), RENT.id);
        await user.selectOptions(screen.getByLabelText('Account 3'), CHECKING.id);
        await user.type(screen.getByLabelText('Debit 1'), '100.00');
        // Row 2 carries both sides filled - its own field error - while row 3 stays valid.
        await user.clear(screen.getByLabelText('Credit 2'));
        await user.type(screen.getByLabelText('Credit 2'), '50.00');
        await user.type(screen.getByLabelText('Debit 2'), '50.00');
        await user.type(screen.getByLabelText('Debit 3'), '25.00');
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));
        await screen.findAllByText('Enter a debit or a credit');

        await user.click(screen.getByRole('button', { name: 'Remove entry 1' }));

        // What was row 2 (Rent) is now shown first, and still carries its own error; what was
        // row 3 (Checking, valid) is now shown second, and carries none.
        expect(screen.getByLabelText('Debit 1')).toHaveProperty('value', '50.00');
        expect(screen.getByLabelText('Debit 1').getAttribute('aria-invalid')).toBe('true');
        expect(screen.getByLabelText('Credit 1').getAttribute('aria-invalid')).toBe('true');
        expect(screen.getByLabelText('Debit 2')).toHaveProperty('value', '25.00');
        expect(screen.getByLabelText('Debit 2').getAttribute('aria-invalid')).toBeNull();
        expect(screen.getByLabelText('Credit 2').getAttribute('aria-invalid')).toBeNull();
    });
});

describe('RecordTransactionPage accounts read', () => {
    it('Given_the_accounts_read_is_pending_When_rendered_Then_a_loading_message_is_shown_and_the_form_is_not', async () => {
        fakeFetch((request) =>
            request.url === ACCOUNTS_URL ? pendingResponse() : unexpectedRequest(request),
        );

        renderForm();

        expect(await screen.findByText('Loading accounts…')).toBeDefined();
        expect(screen.queryByLabelText('Description')).toBeNull();
    });

    it('Given_the_accounts_read_fails_When_rendered_Then_an_error_state_offers_a_retry_that_loads_the_form', async () => {
        let failing = true;
        fakeFetch((request) => {
            if (request.url !== ACCOUNTS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({
                      status: 502,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  })
                : jsonResponse([CHECKING, RENT]);
        });

        renderForm();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        expect(screen.queryByLabelText('Description')).toBeNull();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByLabelText('Description')).toBeDefined();
        // The retry button went with the failure, so focus lands on the form's heading rather than
        // being left on the page body.
        await waitFor(() => {
            expect(document.activeElement).toBe(
                screen.getByRole('heading', { level: 1, name: 'Record a transaction' }),
            );
        });
    });

    it('Given_the_form_already_on_screen_When_a_background_refetch_of_accounts_fails_Then_a_non_blocking_error_state_appears_and_the_form_stays_usable', async () => {
        let listReads = 0;
        fakeFetch((request) => {
            if (request.url !== ACCOUNTS_URL) return unexpectedRequest(request);
            listReads += 1;
            return listReads === 1
                ? jsonResponse([CHECKING, RENT])
                : problemResponse({
                      status: 503,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  });
        });
        const { queryClient } = renderForm();
        await screen.findByLabelText('Description');

        // A failed background refetch never hides data already on screen.
        await act(() => queryClient.invalidateQueries({ queryKey: accountKeys.list }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        expect(screen.getByLabelText('Description')).toBeDefined();
        expect(screen.getByLabelText('Account 1')).toBeDefined();
    });
});

describe('RecordTransactionPage 400 field-error routing', () => {
    it('Given_a_400_naming_description_When_submitted_Then_the_message_lands_on_the_Description_field_and_it_is_focused', async () => {
        fakeFetch(
            ledger((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'description', message: 'must not be blank' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        const error = await screen.findByText('must not be blank');
        const description = screen.getByLabelText('Description');
        expect(description.getAttribute('aria-invalid')).toBe('true');
        expect(description.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(description);
        });
    });

    it('Given_a_400_naming_occurredOn_When_submitted_Then_the_message_lands_on_the_Date_field_and_it_is_focused', async () => {
        fakeFetch(
            ledger((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'occurredOn', message: 'must not be in the future' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        const error = await screen.findByText('must not be in the future');
        const date = screen.getByLabelText('Date');
        expect(date.getAttribute('aria-invalid')).toBe('true');
        expect(date.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(date);
        });
    });

    it('Given_a_400_naming_an_entries_path_When_submitted_Then_the_whole_problem_falls_back_to_an_error_state_and_it_is_focused', async () => {
        fakeFetch(
            ledger((request) => {
                if (request.url === RECORD_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'entries[0].amount', message: 'must not be negative' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderForm();
        const user = userEvent.setup({ delay: null });

        await fillValidTransaction(user);
        await user.click(screen.getByRole('button', { name: 'Record transaction' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Invalid request content.')).toBeDefined();
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['entries[0].amount: must not be negative']);
        expect(screen.getByLabelText('Description').getAttribute('aria-invalid')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });
});
