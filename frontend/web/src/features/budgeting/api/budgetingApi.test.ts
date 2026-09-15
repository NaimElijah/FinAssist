// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';

import {
    addCategorisationRule,
    defineBudget,
    listBudgets,
    listCategorisationRules,
    reviewBudget,
} from './budgetingApi';

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

describe('listBudgets', () => {
    it('Given_a_wire_list_When_listed_Then_it_requests_the_ledger_budgets_as_json_and_maps_each_budget', async () => {
        const stub = stubFetch(
            jsonResponse([
                {
                    id: 'b1',
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'ILS',
                    limits: [
                        { category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } },
                    ],
                    total: { amount: '1000.00', currency: 'ILS' },
                },
            ]),
        );

        const budgets = await listBudgets();

        expect(budgets).toEqual([
            {
                id: 'b1',
                period: { start: '2026-09-01', end: '2026-10-01' },
                currency: 'ILS',
                limits: [{ category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } }],
                total: { amount: '1000.00', currency: 'ILS' },
            },
        ]);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/budgets');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });

    it('Given_a_wire_budget_with_a_member_the_contract_does_not_name_When_listed_Then_it_is_not_carried_over', async () => {
        stubFetch(
            jsonResponse([
                {
                    id: 'b1',
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'ILS',
                    limits: [],
                    total: { amount: '0.00', currency: 'ILS' },
                    extra: 1,
                },
            ]),
        );

        await expect(listBudgets()).resolves.toEqual([
            {
                id: 'b1',
                period: { start: '2026-09-01', end: '2026-10-01' },
                currency: 'ILS',
                limits: [],
                total: { amount: '0.00', currency: 'ILS' },
            },
        ]);
    });
});

describe('defineBudget', () => {
    it('Given_an_input_When_defined_Then_it_posts_the_exact_json_body_and_maps_the_created_budget', async () => {
        const stub = stubFetch(
            jsonResponse(
                {
                    id: 'b1',
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'ILS',
                    limits: [
                        { category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } },
                        { category: 'rent', limit: { amount: '4000.00', currency: 'ILS' } },
                    ],
                    total: { amount: '5000.00', currency: 'ILS' },
                },
                201,
            ),
        );

        const budget = await defineBudget({
            period: { start: '2026-09-01', end: '2026-10-01' },
            currency: 'ILS',
            limits: [
                { category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } },
                { category: 'rent', limit: { amount: '4000.00', currency: 'ILS' } },
            ],
        });

        expect(budget.id).toBe('b1');
        expect(budget.total).toEqual({ amount: '5000.00', currency: 'ILS' });
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/budgets');
        expect(init.method).toBe('POST');
        expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
        expect(JSON.parse(init.body as string)).toEqual({
            period: { start: '2026-09-01', end: '2026-10-01' },
            currency: 'ILS',
            limits: [
                { category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } },
                { category: 'rent', limit: { amount: '4000.00', currency: 'ILS' } },
            ],
        });
    });

    it('Given_a_rule_the_domain_refuses_When_defined_Then_it_rejects_with_an_ApiError_coded_RULE_VIOLATED', async () => {
        stubFetch(
            problemResponse(
                {
                    status: 422,
                    code: 'RULE_VIOLATED',
                    detail: 'A budget needs at least one limit.',
                },
                422,
            ),
        );

        const failure: unknown = await defineBudget({
            period: { start: '2026-09-01', end: '2026-10-01' },
            currency: 'ILS',
            limits: [],
        }).catch((caught: unknown) => caught);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 422, problem: { code: 'RULE_VIOLATED' } });
    });
});

describe('reviewBudget', () => {
    it('Given_a_wire_review_When_read_Then_it_requests_the_review_and_maps_every_member_with_unbudgeted_as_an_array', async () => {
        const stub = stubFetch(
            jsonResponse({
                budget: 'b1',
                period: { start: '2026-09-01', end: '2026-10-01' },
                currency: 'ILS',
                categories: [
                    {
                        category: 'groceries',
                        limit: { amount: '1000.00', currency: 'ILS' },
                        spent: { amount: '1200.00', currency: 'ILS' },
                        remaining: { amount: '-200.00', currency: 'ILS' },
                        overspent: true,
                    },
                ],
                unbudgeted: [
                    { category: 'gifts', spent: { amount: '50.00', currency: 'ILS' } },
                    { category: 'hobbies', spent: { amount: '30.00', currency: 'ILS' } },
                ],
                uncategorised: { amount: '15.00', currency: 'ILS' },
            }),
        );

        const review = await reviewBudget('b1');

        expect(review).toEqual({
            budget: 'b1',
            period: { start: '2026-09-01', end: '2026-10-01' },
            currency: 'ILS',
            categories: [
                {
                    category: 'groceries',
                    limit: { amount: '1000.00', currency: 'ILS' },
                    spent: { amount: '1200.00', currency: 'ILS' },
                    remaining: { amount: '-200.00', currency: 'ILS' },
                    overspent: true,
                },
            ],
            unbudgeted: [
                { category: 'gifts', spent: { amount: '50.00', currency: 'ILS' } },
                { category: 'hobbies', spent: { amount: '30.00', currency: 'ILS' } },
            ],
            uncategorised: { amount: '15.00', currency: 'ILS' },
        });
        expect(Array.isArray(review.unbudgeted)).toBe(true);
        const { url } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/budgets/b1/review');
    });

    it('Given_an_id_carrying_path_characters_When_reviewed_Then_the_id_is_encoded_as_one_segment', async () => {
        const stub = stubFetch(
            jsonResponse({
                budget: 'a/b',
                period: { start: '2026-09-01', end: '2026-10-01' },
                currency: 'ILS',
                categories: [],
                unbudgeted: [],
                uncategorised: { amount: '0.00', currency: 'ILS' },
            }),
        );

        await reviewBudget('a/b');

        expect(onlyCall(stub).url).toBe('/ledger/api/v1/budgets/a%2Fb/review');
    });

    it('Given_an_unknown_budget_When_reviewed_Then_it_rejects_with_an_ApiError_coded_BUDGET_NOT_FOUND', async () => {
        stubFetch(problemResponse({ status: 404, code: 'BUDGET_NOT_FOUND' }, 404));

        await expect(reviewBudget('b1')).rejects.toMatchObject({
            status: 404,
            problem: { code: 'BUDGET_NOT_FOUND' },
        });
    });
});

describe('listCategorisationRules', () => {
    it('Given_a_wire_list_When_listed_Then_it_requests_the_ledger_rules_as_json_and_maps_each_one_in_order', async () => {
        const stub = stubFetch(
            jsonResponse([
                { fragment: 'coffee', category: 'dining' },
                { fragment: 'rent', category: 'housing' },
            ]),
        );

        const rules = await listCategorisationRules();

        expect(rules).toEqual([
            { fragment: 'coffee', category: 'dining' },
            { fragment: 'rent', category: 'housing' },
        ]);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/categorisation-rules');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });
});

describe('addCategorisationRule', () => {
    it('Given_an_input_When_added_Then_it_posts_the_exact_json_body_and_maps_the_created_rule', async () => {
        const stub = stubFetch(jsonResponse({ fragment: 'coffee', category: 'dining' }, 201));

        const rule = await addCategorisationRule({ fragment: 'coffee', category: 'dining' });

        expect(rule).toEqual({ fragment: 'coffee', category: 'dining' });
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/categorisation-rules');
        expect(init.method).toBe('POST');
        expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
        expect(JSON.parse(init.body as string)).toEqual({
            fragment: 'coffee',
            category: 'dining',
        });
    });

    it('Given_a_blank_fragment_When_added_Then_it_rejects_with_an_ApiError_coded_MALFORMED_REQUEST', async () => {
        stubFetch(
            problemResponse(
                {
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [{ field: 'fragment', message: 'must not be blank' }],
                },
                400,
            ),
        );

        const failure: unknown = await addCategorisationRule({
            fragment: '',
            category: 'dining',
        }).catch((caught: unknown) => caught);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 400, problem: { code: 'MALFORMED_REQUEST' } });
    });
});

describe('a response the contract does not describe', () => {
    // The generated types promise five currencies, but a body is unvalidated JSON. Planning and
    // portfolio already refuse a currency they cannot name, and budgeting now agrees with them.

    it('Given_a_budget_whose_currency_the_contract_does_not_name_When_listed_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(
            jsonResponse([
                {
                    id: 'b1',
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'XYZ',
                    limits: [],
                    total: { amount: '0.00', currency: 'ILS' },
                },
            ]),
        );

        await expect(listBudgets()).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_limit_whose_currency_the_contract_does_not_name_When_listed_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(
            jsonResponse([
                {
                    id: 'b1',
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'ILS',
                    limits: [
                        { category: 'groceries', limit: { amount: '1000.00', currency: 'XYZ' } },
                    ],
                    total: { amount: '1000.00', currency: 'ILS' },
                },
            ]),
        );

        await expect(listBudgets()).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_a_review_whose_currency_the_contract_does_not_name_When_read_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(
            jsonResponse({
                budget: 'b1',
                period: { start: '2026-09-01', end: '2026-10-01' },
                currency: 'XYZ',
                categories: [],
                unbudgeted: [],
                uncategorised: { amount: '0.00', currency: 'ILS' },
            }),
        );

        await expect(reviewBudget('b1')).rejects.toBeInstanceOf(ApiBodyError);
    });
});
