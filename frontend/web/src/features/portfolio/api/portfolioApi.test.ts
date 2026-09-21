// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';

import { priceDisposal, readPosition, valueHolding } from './portfolioApi';

const HOLDINGS_URL = '/assistant/api/v1/portfolio/holdings';

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

/** Symbols whose path segment is worth pinning, with the segment each must travel as. */
const ENCODED_SYMBOLS = [
    // A dot is legal in a path segment, so it travels as typed.
    { name: 'a_dot', symbol: 'BRK.B', segment: 'BRK.B' },
    { name: 'a_space', symbol: 'BRK B', segment: 'BRK%20B' },
    // Unencoded, a slash would split the symbol across two segments and reach another route.
    { name: 'a_slash', symbol: 'BRK/B', segment: 'BRK%2FB' },
] as const;

const WIRE_POSITION = {
    symbol: 'VOO',
    quantity: '10.125',
    cost: { amount: '4321.50', currency: 'USD' },
};

const WIRE_VALUATION = {
    symbol: 'VOO',
    quantity: '10.125',
    cost: { amount: '4321.50', currency: 'USD' },
    unitPrice: { amount: '512.34', currency: 'USD' },
    marketValue: { amount: '5187.44', currency: 'USD' },
    unrealisedGain: { amount: '-865.94', currency: 'USD' },
};

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // A failing request is logged by the client; that record is not what is under test here.
    restoreSink = setLogSink(() => undefined);
    // sendJson reads document.cookie for the CSRF header; node has no document at all, and
    // this suite is not exercising that header, so an absent cookie is the right stand-in.
    vi.stubGlobal('document', { cookie: '' });
});

afterEach(() => {
    restoreSink();
    vi.unstubAllGlobals();
});

describe('readPosition', () => {
    it('Given_a_wire_position_When_read_Then_it_gets_the_assistant_position_as_json_and_maps_every_member', async () => {
        const stub = stubFetch(jsonResponse(WIRE_POSITION));

        const position = await readPosition('VOO');

        expect(position).toEqual({
            symbol: 'VOO',
            quantity: '10.125',
            cost: { amount: '4321.50', currency: 'USD' },
        });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(`${HOLDINGS_URL}/VOO/position`);
        expect(init.method ?? 'GET').toBe('GET');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });

    for (const { name, symbol, segment } of ENCODED_SYMBOLS) {
        it(`Given_a_symbol_with_${name}_When_read_Then_it_travels_as_one_encoded_path_segment`, async () => {
            const stub = stubFetch(jsonResponse({ ...WIRE_POSITION, symbol }));

            await readPosition(symbol);

            expect(onlyCall(stub).url).toBe(`${HOLDINGS_URL}/${segment}/position`);
        });
    }

    it('Given_nothing_held_When_read_Then_it_rejects_with_the_HOLDING_NOT_FOUND_problem', async () => {
        stubFetch(
            problemResponse(
                { status: 404, code: 'HOLDING_NOT_FOUND', detail: 'Nothing is held under VOO.' },
                404,
            ),
        );

        const failure = await readPosition('VOO').catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).problem?.code).toBe('HOLDING_NOT_FOUND');
    });

    it('Given_a_cost_currency_the_contract_does_not_name_When_read_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(jsonResponse({ ...WIRE_POSITION, cost: { amount: '1.00', currency: 'XAU' } }));

        await expect(readPosition('VOO')).rejects.toBeInstanceOf(ApiBodyError);
    });
});

describe('valueHolding', () => {
    it('Given_a_wire_valuation_When_valued_Then_it_gets_the_assistant_valuation_and_maps_every_member_with_signs_kept', async () => {
        const stub = stubFetch(jsonResponse(WIRE_VALUATION));

        const valuation = await valueHolding('VOO');

        expect(valuation).toEqual({
            symbol: 'VOO',
            quantity: '10.125',
            cost: { amount: '4321.50', currency: 'USD' },
            unitPrice: { amount: '512.34', currency: 'USD' },
            marketValue: { amount: '5187.44', currency: 'USD' },
            unrealisedGain: { amount: '-865.94', currency: 'USD' },
        });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(`${HOLDINGS_URL}/VOO/valuation`);
        expect(init.method ?? 'GET').toBe('GET');
    });

    for (const { name, symbol, segment } of ENCODED_SYMBOLS) {
        it(`Given_a_symbol_with_${name}_When_valued_Then_it_travels_as_one_encoded_path_segment`, async () => {
            const stub = stubFetch(jsonResponse({ ...WIRE_VALUATION, symbol }));

            await valueHolding(symbol);

            expect(onlyCall(stub).url).toBe(`${HOLDINGS_URL}/${segment}/valuation`);
        });
    }

    it('Given_no_usable_price_When_valued_Then_it_rejects_with_the_PRICE_UNAVAILABLE_problem', async () => {
        stubFetch(problemResponse({ status: 503, code: 'PRICE_UNAVAILABLE' }, 503));

        const failure = await valueHolding('VOO').catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).problem?.code).toBe('PRICE_UNAVAILABLE');
    });

    const MONEY_MEMBERS = ['cost', 'unitPrice', 'marketValue', 'unrealisedGain'] as const;

    for (const member of MONEY_MEMBERS) {
        it(`Given_a_${member}_currency_the_contract_does_not_name_When_valued_Then_it_rejects_with_an_ApiBodyError`, async () => {
            stubFetch(
                jsonResponse({ ...WIRE_VALUATION, [member]: { amount: '1.00', currency: 'XAU' } }),
            );

            await expect(valueHolding('VOO')).rejects.toBeInstanceOf(ApiBodyError);
        });
    }
});

describe('priceDisposal', () => {
    const INPUT = { quantity: '4', proceeds: { amount: '2100.00', currency: 'USD' } } as const;

    const WIRE_DISPOSITION = {
        symbol: 'VOO',
        quantity: '4',
        proceeds: { amount: '2100.00', currency: 'USD' },
        costOfSale: { amount: '1600.00', currency: 'USD' },
        realisedGain: { amount: '500.00', currency: 'USD' },
        remaining: [
            {
                acquiredOn: '2024-03-01',
                quantity: '6',
                unitCost: { amount: '400.00', currency: 'USD' },
            },
            {
                acquiredOn: '2025-01-15',
                quantity: '2.5',
                unitCost: { amount: '450.50', currency: 'USD' },
            },
        ],
    };

    it('Given_a_quantity_and_proceeds_When_priced_Then_it_posts_the_exact_json_body_to_the_disposal_with_no_query_string', async () => {
        const stub = stubFetch(jsonResponse(WIRE_DISPOSITION));

        await priceDisposal('VOO', INPUT);

        const { url, init } = onlyCall(stub);
        // The proceeds are an amount of money, which never travels in a URL (ADR-028).
        expect(url).toBe(`${HOLDINGS_URL}/VOO/disposal`);
        expect(url).not.toContain('?');
        expect(init.method).toBe('POST');
        expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
        expect(JSON.parse(init.body as string)).toEqual({
            quantity: '4',
            proceeds: { amount: '2100.00', currency: 'USD' },
        });
    });

    it('Given_a_wire_disposition_When_priced_Then_every_member_is_mapped_and_the_remaining_lots_keep_their_order', async () => {
        stubFetch(jsonResponse(WIRE_DISPOSITION));

        const disposition = await priceDisposal('VOO', INPUT);

        expect(disposition).toEqual({
            symbol: 'VOO',
            quantity: '4',
            proceeds: { amount: '2100.00', currency: 'USD' },
            costOfSale: { amount: '1600.00', currency: 'USD' },
            realisedGain: { amount: '500.00', currency: 'USD' },
            remaining: [
                {
                    acquiredOn: '2024-03-01',
                    quantity: '6',
                    unitCost: { amount: '400.00', currency: 'USD' },
                },
                {
                    acquiredOn: '2025-01-15',
                    quantity: '2.5',
                    unitCost: { amount: '450.50', currency: 'USD' },
                },
            ],
        });
    });

    for (const { name, symbol, segment } of ENCODED_SYMBOLS) {
        it(`Given_a_symbol_with_${name}_When_priced_Then_it_travels_as_one_encoded_path_segment`, async () => {
            const stub = stubFetch(jsonResponse({ ...WIRE_DISPOSITION, symbol }));

            await priceDisposal(symbol, INPUT);

            expect(onlyCall(stub).url).toBe(`${HOLDINGS_URL}/${segment}/disposal`);
        });
    }

    it('Given_more_units_than_are_held_When_priced_Then_it_rejects_with_the_INSUFFICIENT_HOLDING_problem', async () => {
        stubFetch(problemResponse({ status: 422, code: 'INSUFFICIENT_HOLDING' }, 422));

        const failure = await priceDisposal('VOO', INPUT).catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).problem?.code).toBe('INSUFFICIENT_HOLDING');
    });

    const MONEY_MEMBERS = ['proceeds', 'costOfSale', 'realisedGain'] as const;

    for (const member of MONEY_MEMBERS) {
        it(`Given_a_${member}_currency_the_contract_does_not_name_When_priced_Then_it_rejects_with_an_ApiBodyError`, async () => {
            stubFetch(
                jsonResponse({
                    ...WIRE_DISPOSITION,
                    [member]: { amount: '1.00', currency: 'XAU' },
                }),
            );

            await expect(priceDisposal('VOO', INPUT)).rejects.toBeInstanceOf(ApiBodyError);
        });
    }

    it('Given_a_remaining_lot_whose_unit_cost_currency_the_contract_does_not_name_When_priced_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(
            jsonResponse({
                ...WIRE_DISPOSITION,
                remaining: [
                    {
                        acquiredOn: '2024-03-01',
                        quantity: '6',
                        unitCost: { amount: '400.00', currency: 'XAU' },
                    },
                ],
            }),
        );

        await expect(priceDisposal('VOO', INPUT)).rejects.toBeInstanceOf(ApiBodyError);
    });
});
