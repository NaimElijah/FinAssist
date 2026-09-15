// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';

import { getBalance, listAccounts, listAccountTransactions, openAccount } from './accountingApi';

const CHECKING_ID = '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11';

/**
 * Install a stubbed fetch answering every call with a fresh response from `response`, since a
 * Response body can be read only once. Returns the spy, typed as the real fetch.
 */
function stubFetch(response: () => Response): ReturnType<typeof vi.fn<typeof fetch>> {
    const stub = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(response()));
    vi.stubGlobal('fetch', stub);
    return stub;
}

function jsonResponse(body: unknown, status = 200): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/json' },
        });
}

function problemResponse(body: unknown, status: number): () => Response {
    return () =>
        new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/problem+json' },
        });
}

/** The URL and init of the stub's only call, failing loudly when fetch was not called once. */
function onlyCall(stub: ReturnType<typeof stubFetch>): { url: string; init: RequestInit } {
    expect(stub).toHaveBeenCalledTimes(1);
    const [input, init] = stub.mock.calls[0] ?? [];
    if (typeof input !== 'string') {
        throw new Error('expected fetch to be called with a URL string');
    }
    return { url: input, init: init ?? {} };
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // A failing request is logged by the client; that record is not what is under test here.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
    vi.unstubAllGlobals();
});

describe('listAccounts', () => {
    it('Given_a_wire_list_When_listed_Then_it_requests_the_ledger_accounts_as_json_and_maps_each_account', async () => {
        const stub = stubFetch(
            jsonResponse([
                { id: CHECKING_ID, name: 'Checking', type: 'ASSET', currency: 'ILS' },
                { id: 'e3f1', name: 'Visa', type: 'LIABILITY', currency: 'USD' },
            ]),
        );

        const accounts = await listAccounts();

        expect(accounts).toEqual([
            { id: CHECKING_ID, name: 'Checking', type: 'ASSET', currency: 'ILS' },
            { id: 'e3f1', name: 'Visa', type: 'LIABILITY', currency: 'USD' },
        ]);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/accounts');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });

    it('Given_a_wire_account_with_a_member_the_contract_does_not_name_When_listed_Then_it_is_not_carried_over', async () => {
        // The mapping is explicit: a feature type holds what the feature declares, not whatever
        // the wire happened to send.
        stubFetch(
            jsonResponse([
                { id: CHECKING_ID, name: 'Checking', type: 'ASSET', currency: 'ILS', extra: 1 },
            ]),
        );

        await expect(listAccounts()).resolves.toEqual([
            { id: CHECKING_ID, name: 'Checking', type: 'ASSET', currency: 'ILS' },
        ]);
    });
});

describe('openAccount', () => {
    it('Given_an_input_When_opened_Then_it_posts_json_to_the_accounts_collection_and_maps_the_created_account', async () => {
        const stub = stubFetch(
            jsonResponse(
                { id: CHECKING_ID, name: 'Checking', type: 'ASSET', currency: 'ILS' },
                201,
            ),
        );

        const account = await openAccount({ name: 'Checking', type: 'ASSET', currency: 'ILS' });

        expect(account).toEqual({
            id: CHECKING_ID,
            name: 'Checking',
            type: 'ASSET',
            currency: 'ILS',
        });
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/accounts');
        expect(init.method).toBe('POST');
        expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
        expect(typeof init.body).toBe('string');
        expect(JSON.parse(init.body as string)).toEqual({
            name: 'Checking',
            type: 'ASSET',
            currency: 'ILS',
        });
    });

    it('Given_a_name_already_open_When_opened_Then_it_rejects_with_an_ApiError_coded_DUPLICATE_ACCOUNT_NAME', async () => {
        stubFetch(
            problemResponse(
                {
                    status: 409,
                    code: 'DUPLICATE_ACCOUNT_NAME',
                    detail: 'An account named Checking already exists.',
                },
                409,
            ),
        );

        const failure: unknown = await openAccount({
            name: 'Checking',
            type: 'ASSET',
            currency: 'ILS',
        }).catch((caught: unknown) => caught);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 409, problem: { code: 'DUPLICATE_ACCOUNT_NAME' } });
    });
});

describe('getBalance', () => {
    it('Given_an_account_id_When_its_balance_is_read_Then_it_requests_the_balance_and_returns_the_amount_as_received', async () => {
        const stub = stubFetch(
            jsonResponse({ account: CHECKING_ID, balance: { amount: '-250.00', currency: 'ILS' } }),
        );

        await expect(getBalance(CHECKING_ID)).resolves.toEqual({
            amount: '-250.00',
            currency: 'ILS',
        });
        expect(onlyCall(stub).url).toBe(`/ledger/api/v1/accounts/${CHECKING_ID}/balance`);
    });

    it('Given_an_id_carrying_path_characters_When_its_balance_is_read_Then_the_id_is_encoded_as_one_segment', async () => {
        // An id is data, never part of the path's structure.
        const stub = stubFetch(
            jsonResponse({ account: 'a/b', balance: { amount: '0.00', currency: 'ILS' } }),
        );

        await getBalance('a/b');

        expect(onlyCall(stub).url).toBe('/ledger/api/v1/accounts/a%2Fb/balance');
    });

    it('Given_an_unknown_account_When_its_balance_is_read_Then_it_rejects_with_an_ApiError_coded_ACCOUNT_NOT_FOUND', async () => {
        stubFetch(problemResponse({ status: 404, code: 'ACCOUNT_NOT_FOUND' }, 404));

        await expect(getBalance(CHECKING_ID)).rejects.toMatchObject({
            status: 404,
            problem: { code: 'ACCOUNT_NOT_FOUND' },
        });
    });
});

describe('listAccountTransactions', () => {
    const ENTRIES = [
        { account: CHECKING_ID, amount: { amount: '-250.00', currency: 'ILS' } },
        { account: 'e3f1', amount: { amount: '250.00', currency: 'ILS' } },
    ];

    /** A wire transaction with `id` and `state`, and everything else fixed. */
    function wireTransaction(id: string, state: unknown) {
        return { id, occurredOn: '2026-09-01', description: 'Rent', entries: ENTRIES, state };
    }

    it('Given_every_state_variant_When_listed_Then_each_maps_to_its_own_shape_with_amounts_as_received', async () => {
        const stub = stubFetch(
            jsonResponse([
                wireTransaction('p', { status: 'PENDING' }),
                wireTransaction('c', { status: 'CLEARED', on: '2026-09-02' }),
                wireTransaction('r', { status: 'RECONCILED', on: '2026-09-03' }),
                wireTransaction('v', {
                    status: 'VOIDED',
                    on: '2026-09-04',
                    reason: 'Entered twice',
                }),
            ]),
        );

        const transactions = await listAccountTransactions(CHECKING_ID);

        expect(transactions.map((transaction) => transaction.state)).toEqual([
            { status: 'PENDING' },
            { status: 'CLEARED', on: '2026-09-02' },
            { status: 'RECONCILED', on: '2026-09-03' },
            { status: 'VOIDED', on: '2026-09-04', reason: 'Entered twice' },
        ]);
        expect(transactions[0]).toEqual({
            id: 'p',
            occurredOn: '2026-09-01',
            description: 'Rent',
            entries: [
                { account: CHECKING_ID, amount: { amount: '-250.00', currency: 'ILS' } },
                { account: 'e3f1', amount: { amount: '250.00', currency: 'ILS' } },
            ],
            state: { status: 'PENDING' },
        });
        expect(onlyCall(stub).url).toBe(`/ledger/api/v1/accounts/${CHECKING_ID}/transactions`);
    });

    // TransactionStateResponse.java omits only what a state lacks, so a dated state without its
    // date, or a void without its reason, is the ledger breaking its own mapping.
    const INCOMPLETE_STATES = [
        { name: 'CLEARED_without_its_date', state: { status: 'CLEARED' } },
        { name: 'RECONCILED_without_its_date', state: { status: 'RECONCILED' } },
        { name: 'VOIDED_without_its_date', state: { status: 'VOIDED', reason: 'Entered twice' } },
        { name: 'VOIDED_without_its_reason', state: { status: 'VOIDED', on: '2026-09-04' } },
    ] as const;

    for (const { name, state } of INCOMPLETE_STATES) {
        it(`Given_a_state_${name}_When_listed_Then_it_rejects_with_an_ApiBodyError`, async () => {
            stubFetch(jsonResponse([wireTransaction('x', state)]));

            await expect(listAccountTransactions(CHECKING_ID)).rejects.toBeInstanceOf(ApiBodyError);
        });
    }

    it('Given_a_state_whose_status_the_contract_does_not_name_When_listed_Then_it_rejects_with_an_ApiBodyError', async () => {
        // The generated type promises four statuses, but the body is unvalidated JSON. A status
        // nobody maps would otherwise leave the state undefined for a screen to crash on.
        stubFetch(jsonResponse([wireTransaction('x', { status: 'FROZEN', on: '2026-09-04' })]));

        await expect(listAccountTransactions(CHECKING_ID)).rejects.toBeInstanceOf(ApiBodyError);
    });
});
