// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';

import { readPosition, valueHolding } from './portfolioApi';

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
