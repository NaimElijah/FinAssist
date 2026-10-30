// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals. sendJson does read document.cookie for the CSRF header, so
// stubDocumentCookie stands in for it below rather than pulling in jsdom for the whole file.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';
import { stubDocumentCookie } from '@/shared/testing/stubDocumentCookie';

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
    // This suite is not exercising the CSRF header, so an absent cookie is the right stand-in.
    stubDocumentCookie('');
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

    it('Given_a_dot_dot_symbol_When_a_disposal_is_priced_Then_no_request_is_sent', async () => {
        // `holdings/../disposal` would resolve to a different route, one level up.
        const stub = stubFetch(jsonResponse(WIRE_DISPOSITION));

        await expect(priceDisposal('..', INPUT)).rejects.toThrow(RangeError);

        expect(stub).not.toHaveBeenCalled();
    });

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

/**
 * Values the wire never carries for a decimal (ADR-028): a JSON number, which would pass a pattern
 * test once coerced to text, and a string that is not a decimal at all.
 */
const NOT_DECIMAL_STRINGS: readonly { readonly name: string; readonly value: unknown }[] = [
    { name: 'a_JSON_number', value: 12.5 },
    { name: 'not_a_decimal_string', value: '1,234.50' },
];

/** USD money carrying `amount` as it arrived, unvalidated. */
function money(amount: unknown): { amount: unknown; currency: string } {
    return { amount, currency: 'USD' };
}

/** A position response whose decimals default to valid strings; one may be replaced. */
function decimalPosition({
    quantity = '10.125',
    cost = '4321.50',
}: Partial<Record<'quantity' | 'cost', unknown>> = {}): unknown {
    return { symbol: 'VOO', quantity, cost: money(cost) };
}

type ValuationDecimal = 'quantity' | 'cost' | 'unitPrice' | 'marketValue' | 'unrealisedGain';

/** A valuation response whose decimals default to valid strings; one may be replaced. */
function decimalValuation(replaced: Partial<Record<ValuationDecimal, unknown>> = {}): unknown {
    const value = (member: ValuationDecimal, fallback: string) =>
        member in replaced ? replaced[member] : fallback;
    return {
        symbol: 'VOO',
        quantity: value('quantity', '10.125'),
        cost: money(value('cost', '4321.50')),
        unitPrice: money(value('unitPrice', '512.34')),
        marketValue: money(value('marketValue', '5187.44')),
        unrealisedGain: money(value('unrealisedGain', '-865.94')),
    };
}

type DispositionDecimal =
    'quantity' | 'proceeds' | 'costOfSale' | 'realisedGain' | 'lotQuantity' | 'lotUnitCost';

/** A disposition response whose decimals default to valid strings; one may be replaced. */
function decimalDisposition(replaced: Partial<Record<DispositionDecimal, unknown>> = {}): unknown {
    const value = (member: DispositionDecimal, fallback: string) =>
        member in replaced ? replaced[member] : fallback;
    return {
        symbol: 'VOO',
        quantity: value('quantity', '4'),
        proceeds: money(value('proceeds', '2100.00')),
        costOfSale: money(value('costOfSale', '1600.00')),
        realisedGain: money(value('realisedGain', '500.00')),
        remaining: [
            {
                acquiredOn: '2024-03-01',
                quantity: value('lotQuantity', '6'),
                unitCost: money(value('lotUnitCost', '400.00')),
            },
        ],
    };
}

/** A decimal member, how a response carries `value` in it alone, and the read that maps it. */
interface DecimalMember {
    readonly subject: string;
    readonly when: string;
    readonly body: (value: unknown) => unknown;
    readonly read: () => Promise<unknown>;
}

const DISPOSAL_INPUT = { quantity: '4', proceeds: { amount: '2100.00', currency: 'USD' } } as const;

/** Every decimal member each read returns. */
const DECIMAL_MEMBERS: readonly DecimalMember[] = [
    {
        subject: 'a_positions_quantity',
        when: 'read',
        body: (value) => decimalPosition({ quantity: value }),
        read: () => readPosition('VOO'),
    },
    {
        subject: 'a_positions_cost_amount',
        when: 'read',
        body: (value) => decimalPosition({ cost: value }),
        read: () => readPosition('VOO'),
    },
    ...(
        [
            ['quantity', 'quantity'],
            ['cost', 'cost_amount'],
            ['unitPrice', 'unit_price_amount'],
            ['marketValue', 'market_value_amount'],
            ['unrealisedGain', 'unrealised_gain_amount'],
        ] as const
    ).map(([member, name]) => ({
        subject: `a_valuations_${name}`,
        when: 'valued',
        body: (value: unknown) => decimalValuation({ [member]: value }),
        read: () => valueHolding('VOO'),
    })),
    ...(
        [
            ['quantity', 'quantity'],
            ['proceeds', 'proceeds_amount'],
            ['costOfSale', 'cost_of_sale_amount'],
            ['realisedGain', 'realised_gain_amount'],
            ['lotQuantity', 'remaining_lots_quantity'],
            ['lotUnitCost', 'remaining_lots_unit_cost_amount'],
        ] as const
    ).map(([member, name]) => ({
        subject: `a_dispositions_${name}`,
        when: 'priced',
        body: (value: unknown) => decimalDisposition({ [member]: value }),
        read: () => priceDisposal('VOO', DISPOSAL_INPUT),
    })),
];

describe('a decimal the contract does not allow', () => {
    // Accounting already refuses one (accountingApi.test.ts). Refused at the read, because past it
    // a JSON number fails nowhere: JSON.parse has already made it a binary float, rounding any
    // figure it cannot hold exactly, MoneyText's pattern passes it once coerced to text, and a
    // quantity renders as it came - so an inexact figure would be shown silently, as if exact.
    it('Given_every_fixture_with_valid_decimals_When_read_Then_each_read_succeeds', async () => {
        // The control for the tests below: each fixture is a valid response but for the one
        // member a test replaces, so a rejection there is that member's doing.
        for (const { body, read } of DECIMAL_MEMBERS) {
            stubFetch(jsonResponse(body('1.00')));
            await expect(read()).resolves.toBeDefined();
        }
    });

    for (const { subject, when, body, read } of DECIMAL_MEMBERS) {
        for (const { name, value } of NOT_DECIMAL_STRINGS) {
            it(`Given_${subject}_that_is_${name}_When_${when}_Then_it_rejects_with_an_ApiBodyError`, async () => {
                stubFetch(jsonResponse(body(value)));

                await expect(read()).rejects.toBeInstanceOf(ApiBodyError);
            });
        }
    }
});
