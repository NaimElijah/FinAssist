// @vitest-environment jsdom
// Renders the accounts screen through the feature's own routes and drives its form, so it needs a
// DOM. fetch is stubbed, so the feature's real api/ module and its mapping run under test.

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    type RecordedRequest,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { accountingRoutes } from '../index';

const ACCOUNTS_URL = '/ledger/api/v1/accounts';
const BALANCE_URL = /^\/ledger\/api\/v1\/accounts\/([^/]+)\/balance$/;

const CHECKING = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    name: 'Checking',
    type: 'ASSET',
    currency: 'USD',
} as const;
const SAVINGS = {
    id: '5c0e8f7a-1b2d-4e3f-8a9b-0c1d2e3f4a5b',
    name: 'Savings',
    type: 'ASSET',
    currency: 'ILS',
} as const;

/** The balance request's account id, or `undefined` when `request` is not a balance read. */
function balanceIdOf(request: RecordedRequest): string | undefined {
    return BALANCE_URL.exec(request.url)?.[1];
}

/** The table row an account's name heads. */
function rowOf(name: string): HTMLElement {
    const row = screen.getByRole('rowheader', { name }).closest('tr');
    if (row === null) {
        throw new Error(`no table row is headed by ${name}`);
    }
    return row;
}

/** `rowOf`, waiting for the row to appear first. */
async function findRow(name: string): Promise<HTMLElement> {
    await screen.findByRole('rowheader', { name });
    return rowOf(name);
}

function renderAccounts() {
    return renderRoutes(accountingRoutes, '/accounts');
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

describe('AccountsPage', () => {
    it('Given_accounts_served_in_an_order_When_the_page_renders_Then_rows_keep_that_order_with_formatted_balances', async () => {
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL) return jsonResponse([SAVINGS, CHECKING]);
            if (balanceIdOf(request) === SAVINGS.id) {
                return jsonResponse({
                    account: SAVINGS.id,
                    balance: { amount: '1234.50', currency: 'ILS' },
                });
            }
            if (balanceIdOf(request) === CHECKING.id) {
                return jsonResponse({
                    account: CHECKING.id,
                    balance: { amount: '-12.00', currency: 'USD' },
                });
            }
            return unexpectedRequest(request);
        });

        renderAccounts();

        expect(await screen.findByRole('heading', { level: 1, name: 'Accounts' })).toBeDefined();
        await screen.findByRole('table', { name: 'Accounts' });
        expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
            'Savings',
            'Checking',
        ]);
        expect(await within(rowOf('Savings')).findByText('₪1,234.50')).toBeDefined();
        expect(await within(rowOf('Checking')).findByText('-US$12.00')).toBeDefined();
        expect(within(rowOf('Checking')).getByText('Asset')).toBeDefined();
        expect(within(rowOf('Checking')).getByText('USD')).toBeDefined();
        expect(screen.getByRole('link', { name: 'Checking' }).getAttribute('href')).toBe(
            `/accounts/${CHECKING.id}`,
        );
    });

    it('Given_no_accounts_When_the_page_renders_Then_the_empty_state_invites_opening_one', async () => {
        fakeFetch((request) =>
            request.url === ACCOUNTS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderAccounts();

        expect(await screen.findByText('No accounts yet — open one below')).toBeDefined();
        expect(screen.queryByRole('table')).toBeNull();
    });

    it('Given_one_balance_fails_When_the_page_renders_Then_only_that_row_shows_unavailable', async () => {
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL) return jsonResponse([CHECKING, SAVINGS]);
            if (balanceIdOf(request) === CHECKING.id) {
                return problemResponse({ status: 500, code: 'INTERNAL_ERROR' });
            }
            if (balanceIdOf(request) === SAVINGS.id) {
                return jsonResponse({
                    account: SAVINGS.id,
                    balance: { amount: '10.00', currency: 'ILS' },
                });
            }
            return unexpectedRequest(request);
        });

        renderAccounts();

        expect(await within(await findRow('Checking')).findByText('Unavailable')).toBeDefined();
        expect(await within(rowOf('Savings')).findByText('₪10.00')).toBeDefined();
        expect(within(rowOf('Savings')).queryByText('Unavailable')).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_the_list_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url !== ACCOUNTS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({
                      status: 503,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  })
                : jsonResponse([]);
        });

        renderAccounts();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        failing = false;
        await userEvent.setup().click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No accounts yet — open one below')).toBeDefined();
        expect(requests.filter((request) => request.url === ACCOUNTS_URL)).toHaveLength(2);
    });
});

describe('AccountsPage opening an account', () => {
    /** Type a name, pick a type and a currency, and submit. */
    async function openVisa(user: ReturnType<typeof userEvent.setup>) {
        await user.type(await screen.findByLabelText('Name'), 'Visa');
        await user.selectOptions(screen.getByLabelText('Type'), 'LIABILITY');
        await user.selectOptions(screen.getByLabelText('Currency'), 'USD');
        await user.click(screen.getByRole('button', { name: 'Open account' }));
    }

    it('Given_the_form_When_an_account_is_opened_Then_it_posts_the_body_and_the_refetched_list_shows_it', async () => {
        const VISA = {
            id: '9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a',
            name: 'Visa',
            type: 'LIABILITY',
            currency: 'USD',
        };
        const served: unknown[] = [CHECKING];
        const requests = fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                served.push(VISA);
                return jsonResponse(VISA, 201);
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([...served]);
            const id = balanceIdOf(request);
            if (id !== undefined)
                return jsonResponse({ account: id, balance: { amount: '0.00', currency: 'USD' } });
            return unexpectedRequest(request);
        });
        renderAccounts();
        await screen.findByRole('link', { name: 'Checking' });

        await openVisa(userEvent.setup());

        expect(await screen.findByRole('link', { name: 'Visa' })).toBeDefined();
        const posts = requests.filter((request) => request.method === 'POST');
        expect(posts).toEqual([
            {
                method: 'POST',
                url: ACCOUNTS_URL,
                body: { name: 'Visa', type: 'LIABILITY', currency: 'USD' },
            },
        ]);
        expect(
            requests.filter((request) => request.method === 'GET' && request.url === ACCOUNTS_URL),
        ).toHaveLength(2);
    });

    it('Given_balances_on_screen_When_an_account_is_opened_Then_only_the_list_is_refetched_and_no_shown_balance_is_requested_again', async () => {
        // Opening an account posts no entry, so no balance on screen can have changed.
        const VISA = {
            id: '9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a',
            name: 'Visa',
            type: 'LIABILITY',
            currency: 'USD',
        };
        const served: unknown[] = [CHECKING, SAVINGS];
        const requests = fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                served.push(VISA);
                return jsonResponse(VISA, 201);
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([...served]);
            const id = balanceIdOf(request);
            if (id !== undefined) {
                return jsonResponse({ account: id, balance: { amount: '0.00', currency: 'USD' } });
            }
            return unexpectedRequest(request);
        });
        renderAccounts();
        await within(await findRow('Checking')).findByText('US$0.00');
        await within(rowOf('Savings')).findByText('US$0.00');
        const beforeOpening = requests.length;

        await openVisa(userEvent.setup());
        await within(await findRow('Visa')).findByText('US$0.00');

        expect(
            requests.slice(beforeOpening).map((request) => `${request.method} ${request.url}`),
        ).toEqual([
            `POST ${ACCOUNTS_URL}`,
            `GET ${ACCOUNTS_URL}`,
            `GET ${ACCOUNTS_URL}/${VISA.id}/balance`,
        ]);
    });

    it('Given_an_account_was_opened_When_the_list_shows_it_Then_the_form_is_reset_and_focus_is_on_its_link', async () => {
        const VISA = {
            id: '9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a',
            name: 'Visa',
            type: 'LIABILITY',
            currency: 'USD',
        };
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                served.push(VISA);
                return jsonResponse(VISA, 201);
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([...served]);
            const id = balanceIdOf(request);
            if (id !== undefined)
                return jsonResponse({ account: id, balance: { amount: '0.00', currency: 'USD' } });
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const link = await screen.findByRole('link', { name: 'Visa' });
        await waitFor(() => {
            expect(document.activeElement).toBe(link);
        });
        expect(screen.getByLabelText('Name')).toHaveProperty('value', '');
        expect(screen.getByLabelText('Type')).toHaveProperty('value', 'ASSET');
        expect(screen.getByLabelText('Currency')).toHaveProperty('value', 'ILS');
    });

    it('Given_a_duplicate_name_When_opened_Then_the_server_detail_is_the_Name_field_error', async () => {
        const detail = 'An account named Visa already exists.';
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                return problemResponse({ status: 409, code: 'DUPLICATE_ACCOUNT_NAME', detail });
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const error = await screen.findByText(detail);
        const name = screen.getByLabelText('Name');
        expect(name.getAttribute('aria-invalid')).toBe('true');
        expect(name.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_400_listing_field_errors_When_opened_Then_each_message_is_on_its_field', async () => {
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [
                        { field: 'type', message: 'must be one of the five types' },
                        { field: 'currency', message: 'is not supported' },
                    ],
                });
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const typeError = await screen.findByText('must be one of the five types');
        expect(screen.getByLabelText('Type').getAttribute('aria-describedby')).toBe(typeError.id);
        const currencyError = screen.getByText('is not supported');
        expect(screen.getByLabelText('Currency').getAttribute('aria-describedby')).toBe(
            currencyError.id,
        );
        expect(screen.getByLabelText('Name').getAttribute('aria-invalid')).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_400_without_field_errors_When_opened_Then_its_detail_is_an_error_state_above_the_form', async () => {
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'The request body could not be read.',
                });
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The request body could not be read.')).toBeDefined();
        const form = screen.getByLabelText('Name').closest('form');
        expect(form).not.toBeNull();
        expect(
            alert.compareDocumentPosition(form as Node) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
        expect(screen.getByLabelText('Name').getAttribute('aria-invalid')).toBeNull();
    });

    it('Given_an_open_request_in_flight_When_submitted_again_Then_submit_is_disabled_and_nothing_is_sent_twice', async () => {
        const requests = fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                // Never answered: the request stays in flight for the rest of the test.
                return pendingResponse();
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const submit = screen.getByRole('button', { name: 'Open account' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        const form = screen.getByLabelText('Name').closest('form');
        if (form === null) {
            throw new Error('the Name field is not inside a form');
        }
        // A disabled button stops a click, not a submit event from elsewhere, such as Enter.
        fireEvent.submit(form);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_a_400_whose_field_errors_name_a_field_the_form_lacks_When_opened_Then_the_whole_problem_is_an_error_state', async () => {
        // Placing only the errors the form recognises would silently drop the rest.
        fakeFetch((request) => {
            if (request.url === ACCOUNTS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [
                        { field: 'name', message: 'must not be blank' },
                        { field: 'openAccountRequest', message: 'is not readable' },
                    ],
                });
            }
            if (request.url === ACCOUNTS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderAccounts();

        await openVisa(userEvent.setup());

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Invalid request content.')).toBeDefined();
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['name: must not be blank', 'openAccountRequest: is not readable']);
        expect(screen.getByLabelText('Name').getAttribute('aria-invalid')).toBeNull();
    });
});
