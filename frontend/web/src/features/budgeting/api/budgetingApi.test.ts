// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals. sendJson does read document.cookie for the CSRF header, so
// stubDocumentCookie stands in for it below rather than pulling in jsdom for the whole file.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';
import { stubDocumentCookie } from '@/shared/testing/stubDocumentCookie';

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
    // This suite is not exercising the CSRF header, so an absent cookie is the right stand-in.
    stubDocumentCookie('');
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

/**
 * Values the wire never carries for a decimal (ADR-028): a JSON number, which would pass a pattern
 * test once coerced to text, and a string that is not a decimal at all.
 */
const NOT_DECIMAL_STRINGS: readonly { readonly name: string; readonly value: unknown }[] = [
    { name: 'a_JSON_number', value: 12.5 },
    { name: 'not_a_decimal_string', value: '1,234.50' },
];

/** ILS money carrying `amount` as it arrived, unvalidated. */
function money(amount: unknown): { amount: unknown; currency: string } {
    return { amount, currency: 'ILS' };
}

/** A budget response whose limit and total amounts default to valid decimal strings. */
function wireBudget({
    limit = '1000.00',
    total = '1000.00',
}: { limit?: unknown; total?: unknown } = {}): unknown {
    return {
        id: 'b1',
        period: { start: '2026-09-01', end: '2026-10-01' },
        currency: 'ILS',
        limits: [{ category: 'groceries', limit: money(limit) }],
        total: money(total),
    };
}

/** A review response whose every amount defaults to a valid decimal string. */
function wireReview({
    limit = '1000.00',
    spent = '1200.00',
    remaining = '-200.00',
    unbudgeted = '50.00',
    uncategorised = '15.00',
}: {
    limit?: unknown;
    spent?: unknown;
    remaining?: unknown;
    unbudgeted?: unknown;
    uncategorised?: unknown;
} = {}): unknown {
    return {
        budget: 'b1',
        period: { start: '2026-09-01', end: '2026-10-01' },
        currency: 'ILS',
        categories: [
            {
                category: 'groceries',
                limit: money(limit),
                spent: money(spent),
                remaining: money(remaining),
                overspent: true,
            },
        ],
        unbudgeted: [{ category: 'gifts', spent: money(unbudgeted) }],
        uncategorised: money(uncategorised),
    };
}

const DEFINE_INPUT = {
    period: { start: '2026-09-01', end: '2026-10-01' },
    currency: 'ILS',
    limits: [{ category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } }],
} as const;

/**
 * Every decimal member each read returns: the response carrying `value` in that member alone, and
 * the read that maps it.
 */
const DECIMAL_MEMBERS: readonly {
    readonly subject: string;
    readonly when: string;
    readonly body: (value: unknown) => unknown;
    readonly status?: number;
    readonly read: () => Promise<unknown>;
}[] = [
    {
        subject: 'a_listed_budgets_limit_amount',
        when: 'listed',
        body: (value) => [wireBudget({ limit: value })],
        read: () => listBudgets(),
    },
    {
        subject: 'a_listed_budgets_total_amount',
        when: 'listed',
        body: (value) => [wireBudget({ total: value })],
        read: () => listBudgets(),
    },
    {
        subject: 'a_defined_budgets_limit_amount',
        when: 'defined',
        body: (value) => wireBudget({ limit: value }),
        status: 201,
        read: () => defineBudget(DEFINE_INPUT),
    },
    {
        subject: 'a_defined_budgets_total_amount',
        when: 'defined',
        body: (value) => wireBudget({ total: value }),
        status: 201,
        read: () => defineBudget(DEFINE_INPUT),
    },
    {
        subject: 'a_reviewed_categorys_limit_amount',
        when: 'reviewed',
        body: (value) => wireReview({ limit: value }),
        read: () => reviewBudget('b1'),
    },
    {
        subject: 'a_reviewed_categorys_spent_amount',
        when: 'reviewed',
        body: (value) => wireReview({ spent: value }),
        read: () => reviewBudget('b1'),
    },
    {
        subject: 'a_reviewed_categorys_remaining_amount',
        when: 'reviewed',
        body: (value) => wireReview({ remaining: value }),
        read: () => reviewBudget('b1'),
    },
    {
        subject: 'a_reviews_unbudgeted_spent_amount',
        when: 'reviewed',
        body: (value) => wireReview({ unbudgeted: value }),
        read: () => reviewBudget('b1'),
    },
    {
        subject: 'a_reviews_uncategorised_amount',
        when: 'reviewed',
        body: (value) => wireReview({ uncategorised: value }),
        read: () => reviewBudget('b1'),
    },
];

describe('a decimal the contract does not allow', () => {
    // Accounting already refuses one (accountingApi.test.ts). A JSON number would otherwise reach
    // MoneyText already rounded by JSON.parse and be shown silently as if exact; a malformed string
    // would throw at render, replacing the whole area instead of failing one read.
    it('Given_every_fixture_with_valid_amounts_When_read_Then_each_read_succeeds', async () => {
        // The control for the tests below: each fixture is a valid response but for the one
        // member a test replaces, so a rejection there is that member's doing.
        for (const { body, status, read } of DECIMAL_MEMBERS) {
            stubFetch(jsonResponse(body('1.00'), status));
            await expect(read()).resolves.toBeDefined();
        }
    });

    for (const { subject, when, body, status, read } of DECIMAL_MEMBERS) {
        for (const { name, value } of NOT_DECIMAL_STRINGS) {
            it(`Given_${subject}_that_is_${name}_When_${when}_Then_it_rejects_with_an_ApiBodyError`, async () => {
                stubFetch(jsonResponse(body(value), status));

                await expect(read()).rejects.toBeInstanceOf(ApiBodyError);
            });
        }
    }
});
