// @vitest-environment node
// No DOM: these functions only call fetch and map what it returns, and fetch, Headers and
// Response are Node globals.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiBodyError, ApiError } from '@/shared/api/client';
import { setLogSink } from '@/shared/lib/logger';

import {
    cancelFlow,
    commitFlow,
    defineScenario,
    listPlannedFlows,
    listScenarios,
    planFlow,
    realiseFlow,
} from './planningApi';

const FLOW_ID = '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11';

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

describe('listPlannedFlows', () => {
    const AMOUNT = { amount: '250.00', currency: 'ILS' };

    /** A wire planned flow with `id`, `recurrence` and `state`, and everything else fixed. */
    function wireFlow(id: string, recurrence: unknown, state: unknown) {
        return {
            id,
            description: 'Rent',
            amount: AMOUNT,
            direction: 'OUTFLOW',
            startsOn: '2026-09-01',
            recurrence,
            state,
        };
    }

    it('Given_a_wire_list_When_listed_Then_it_requests_the_ledger_planned_flows_as_json_and_maps_each_flow', async () => {
        const stub = stubFetch(
            jsonResponse([wireFlow(FLOW_ID, { kind: 'ONCE' }, { status: 'PLANNED' })]),
        );

        const flows = await listPlannedFlows();

        expect(flows).toEqual([
            {
                id: FLOW_ID,
                description: 'Rent',
                amount: { amount: '250.00', currency: 'ILS' },
                direction: 'OUTFLOW',
                startsOn: '2026-09-01',
                recurrence: { kind: 'ONCE' },
                state: { status: 'PLANNED' },
            },
        ]);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/planned-flows');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });

    it('Given_every_state_variant_When_listed_Then_each_maps_to_its_own_shape_with_dates_and_reasons_kept', async () => {
        stubFetch(
            jsonResponse([
                wireFlow('a', { kind: 'ONCE' }, { status: 'PLANNED' }),
                wireFlow('b', { kind: 'ONCE' }, { status: 'COMMITTED', on: '2026-09-02' }),
                wireFlow('c', { kind: 'ONCE' }, { status: 'REALISED', on: '2026-09-03' }),
                wireFlow(
                    'd',
                    { kind: 'ONCE' },
                    {
                        status: 'CANCELLED',
                        on: '2026-09-04',
                        reason: 'No longer needed',
                    },
                ),
            ]),
        );

        const flows = await listPlannedFlows();

        expect(flows.map((flow) => flow.state)).toEqual([
            { status: 'PLANNED' },
            { status: 'COMMITTED', on: '2026-09-02' },
            { status: 'REALISED', on: '2026-09-03' },
            { status: 'CANCELLED', on: '2026-09-04', reason: 'No longer needed' },
        ]);
    });

    const INCOMPLETE_STATES = [
        { name: 'COMMITTED_without_its_date', state: { status: 'COMMITTED' } },
        { name: 'REALISED_without_its_date', state: { status: 'REALISED' } },
        {
            name: 'CANCELLED_without_its_date',
            state: { status: 'CANCELLED', reason: 'No longer needed' },
        },
        { name: 'CANCELLED_without_its_reason', state: { status: 'CANCELLED', on: '2026-09-04' } },
    ] as const;

    for (const { name, state } of INCOMPLETE_STATES) {
        it(`Given_a_state_${name}_When_listed_Then_it_rejects_with_an_ApiBodyError`, async () => {
            stubFetch(jsonResponse([wireFlow('x', { kind: 'ONCE' }, state)]));

            await expect(listPlannedFlows()).rejects.toBeInstanceOf(ApiBodyError);
        });
    }

    it('Given_a_state_whose_status_the_contract_does_not_name_When_listed_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(
            jsonResponse([wireFlow('x', { kind: 'ONCE' }, { status: 'FROZEN', on: '2026-09-04' })]),
        );

        await expect(listPlannedFlows()).rejects.toBeInstanceOf(ApiBodyError);
    });

    it('Given_every_recurrence_variant_When_listed_Then_each_maps_to_its_own_shape', async () => {
        stubFetch(
            jsonResponse([
                wireFlow('once', { kind: 'ONCE' }, { status: 'PLANNED' }),
                wireFlow(
                    'until',
                    { kind: 'UNTIL', cadence: 'WEEKLY', interval: 2, until: '2027-01-01' },
                    { status: 'PLANNED' },
                ),
                wireFlow(
                    'times',
                    { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
                    { status: 'PLANNED' },
                ),
            ]),
        );

        const flows = await listPlannedFlows();

        expect(flows.map((flow) => flow.recurrence)).toEqual([
            { kind: 'ONCE' },
            { kind: 'UNTIL', cadence: 'WEEKLY', interval: 2, until: '2027-01-01' },
            { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
        ]);
    });

    const INCOMPLETE_RECURRENCES = [
        {
            name: 'UNTIL_without_its_cadence',
            recurrence: { kind: 'UNTIL', interval: 2, until: 'x' },
        },
        {
            name: 'UNTIL_without_its_interval',
            recurrence: { kind: 'UNTIL', cadence: 'WEEKLY', until: 'x' },
        },
        {
            name: 'UNTIL_without_its_until',
            recurrence: { kind: 'UNTIL', cadence: 'WEEKLY', interval: 2 },
        },
        {
            name: 'TIMES_without_its_count',
            recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1 },
        },
    ] as const;

    for (const { name, recurrence } of INCOMPLETE_RECURRENCES) {
        it(`Given_a_recurrence_${name}_When_listed_Then_it_rejects_with_an_ApiBodyError`, async () => {
            stubFetch(jsonResponse([wireFlow('x', recurrence, { status: 'PLANNED' })]));

            await expect(listPlannedFlows()).rejects.toBeInstanceOf(ApiBodyError);
        });
    }

    it('Given_a_recurrence_whose_kind_the_contract_does_not_name_When_listed_Then_it_rejects_with_an_ApiBodyError', async () => {
        stubFetch(jsonResponse([wireFlow('x', { kind: 'DAILY' }, { status: 'PLANNED' })]));

        await expect(listPlannedFlows()).rejects.toBeInstanceOf(ApiBodyError);
    });
});

describe('planFlow', () => {
    it('Given_a_once_input_When_planned_Then_it_posts_the_exact_json_body_with_recurrence_holding_only_kind', async () => {
        const stub = stubFetch(
            jsonResponse(
                {
                    id: FLOW_ID,
                    description: 'Rent',
                    amount: { amount: '250.00', currency: 'ILS' },
                    direction: 'OUTFLOW',
                    startsOn: '2026-09-01',
                    recurrence: { kind: 'ONCE' },
                    state: { status: 'PLANNED' },
                },
                201,
            ),
        );

        const flow = await planFlow({
            description: 'Rent',
            amount: { amount: '250.00', currency: 'ILS' },
            direction: 'OUTFLOW',
            startsOn: '2026-09-01',
            recurrence: { kind: 'ONCE' },
        });

        expect(flow.id).toBe(FLOW_ID);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/planned-flows');
        expect(init.method).toBe('POST');
        expect(JSON.parse(init.body as string)).toEqual({
            description: 'Rent',
            amount: { amount: '250.00', currency: 'ILS' },
            direction: 'OUTFLOW',
            startsOn: '2026-09-01',
            recurrence: { kind: 'ONCE' },
        });
    });

    it('Given_a_times_input_When_planned_Then_the_posted_recurrence_holds_only_its_own_members', async () => {
        const stub = stubFetch(
            jsonResponse(
                {
                    id: FLOW_ID,
                    description: 'Salary',
                    amount: { amount: '1000.00', currency: 'ILS' },
                    direction: 'INFLOW',
                    startsOn: '2026-09-01',
                    recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
                    state: { status: 'PLANNED' },
                },
                201,
            ),
        );

        await planFlow({
            description: 'Salary',
            amount: { amount: '1000.00', currency: 'ILS' },
            direction: 'INFLOW',
            startsOn: '2026-09-01',
            recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
        });

        const body = JSON.parse(onlyCall(stub).init.body as string) as Record<string, unknown>;
        expect(body.recurrence).toEqual({
            kind: 'TIMES',
            cadence: 'MONTHLY',
            interval: 1,
            count: 12,
        });
        expect(Object.keys(body.recurrence as object)).toEqual([
            'kind',
            'cadence',
            'interval',
            'count',
        ]);
    });

    it('Given_a_negative_amount_When_planned_Then_it_is_sent_exactly_as_typed_without_normalisation', async () => {
        const stub = stubFetch(
            problemResponse(
                { status: 422, code: 'RULE_VIOLATED', detail: 'The amount must be positive.' },
                422,
            ),
        );

        const failure: unknown = await planFlow({
            description: 'Rent',
            amount: { amount: '-250.00', currency: 'ILS' },
            direction: 'OUTFLOW',
            startsOn: '2026-09-01',
            recurrence: { kind: 'ONCE' },
        }).catch((caught: unknown) => caught);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 422, problem: { code: 'RULE_VIOLATED' } });
        const body = JSON.parse(onlyCall(stub).init.body as string) as Record<string, unknown>;
        expect(body.amount).toEqual({ amount: '-250.00', currency: 'ILS' });
    });

    it('Given_an_incomplete_recurrence_400_When_planned_Then_it_rejects_with_an_ApiError_naming_the_field', async () => {
        stubFetch(
            problemResponse(
                {
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [
                        {
                            field: 'recurrence.completeForKind',
                            message: "must carry exactly its kind's members",
                        },
                    ],
                },
                400,
            ),
        );

        const failure: unknown = await planFlow({
            description: 'Rent',
            amount: { amount: '250.00', currency: 'ILS' },
            direction: 'OUTFLOW',
            startsOn: '2026-09-01',
            recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
        }).catch((caught: unknown) => caught);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({
            status: 400,
            problem: { errors: [{ field: 'recurrence.completeForKind' }] },
        });
    });
});

describe('commitFlow', () => {
    it('Given_a_flow_id_and_a_date_When_committed_Then_it_posts_on_to_the_commit_path_and_maps_the_result', async () => {
        const stub = stubFetch(
            jsonResponse({
                id: FLOW_ID,
                description: 'Rent',
                amount: { amount: '250.00', currency: 'ILS' },
                direction: 'OUTFLOW',
                startsOn: '2026-09-01',
                recurrence: { kind: 'ONCE' },
                state: { status: 'COMMITTED', on: '2026-09-02' },
            }),
        );

        const flow = await commitFlow(FLOW_ID, '2026-09-02');

        expect(flow.state).toEqual({ status: 'COMMITTED', on: '2026-09-02' });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(`/ledger/api/v1/planned-flows/${FLOW_ID}/commit`);
        expect(init.method).toBe('POST');
        expect(JSON.parse(init.body as string)).toEqual({ on: '2026-09-02' });
    });

    it('Given_an_illegal_transition_When_committed_Then_it_rejects_with_an_ApiError_coded_ILLEGAL_TRANSITION', async () => {
        stubFetch(problemResponse({ status: 409, code: 'ILLEGAL_TRANSITION' }, 409));

        await expect(commitFlow(FLOW_ID, '2026-09-02')).rejects.toMatchObject({
            status: 409,
            problem: { code: 'ILLEGAL_TRANSITION' },
        });
    });
});

describe('realiseFlow', () => {
    it('Given_a_flow_id_and_a_date_When_realised_Then_it_posts_on_to_the_realise_path_and_maps_the_result', async () => {
        const stub = stubFetch(
            jsonResponse({
                id: FLOW_ID,
                description: 'Rent',
                amount: { amount: '250.00', currency: 'ILS' },
                direction: 'OUTFLOW',
                startsOn: '2026-09-01',
                recurrence: { kind: 'ONCE' },
                state: { status: 'REALISED', on: '2026-09-03' },
            }),
        );

        const flow = await realiseFlow(FLOW_ID, '2026-09-03');

        expect(flow.state).toEqual({ status: 'REALISED', on: '2026-09-03' });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(`/ledger/api/v1/planned-flows/${FLOW_ID}/realise`);
        expect(JSON.parse(init.body as string)).toEqual({ on: '2026-09-03' });
    });
});

describe('cancelFlow', () => {
    it('Given_a_flow_id_a_date_and_a_reason_When_cancelled_Then_it_posts_both_to_the_cancel_path_and_maps_the_result', async () => {
        const stub = stubFetch(
            jsonResponse({
                id: FLOW_ID,
                description: 'Rent',
                amount: { amount: '250.00', currency: 'ILS' },
                direction: 'OUTFLOW',
                startsOn: '2026-09-01',
                recurrence: { kind: 'ONCE' },
                state: { status: 'CANCELLED', on: '2026-09-04', reason: 'No longer needed' },
            }),
        );

        const flow = await cancelFlow(FLOW_ID, '2026-09-04', 'No longer needed');

        expect(flow.state).toEqual({
            status: 'CANCELLED',
            on: '2026-09-04',
            reason: 'No longer needed',
        });
        const { url, init } = onlyCall(stub);
        expect(url).toBe(`/ledger/api/v1/planned-flows/${FLOW_ID}/cancel`);
        expect(JSON.parse(init.body as string)).toEqual({
            on: '2026-09-04',
            reason: 'No longer needed',
        });
    });
});

describe('listScenarios', () => {
    it('Given_a_wire_list_When_listed_Then_it_requests_the_ledger_scenarios_as_json_and_maps_each_scenario', async () => {
        const stub = stubFetch(
            jsonResponse([
                {
                    id: 's1',
                    name: 'Baseline',
                    currency: 'ILS',
                    flows: [],
                    growthRatePerStep: '0',
                },
                {
                    id: 's2',
                    name: 'Aggressive saving',
                    currency: 'ILS',
                    flows: [FLOW_ID],
                    growthRatePerStep: '0.005',
                },
            ]),
        );

        const scenarios = await listScenarios();

        expect(scenarios).toEqual([
            { id: 's1', name: 'Baseline', currency: 'ILS', flows: [], growthRatePerStep: '0' },
            {
                id: 's2',
                name: 'Aggressive saving',
                currency: 'ILS',
                flows: [FLOW_ID],
                growthRatePerStep: '0.005',
            },
        ]);
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/scenarios');
        expect(new Headers(init.headers).get('Accept')).toBe('application/json');
    });
});

describe('defineScenario', () => {
    it('Given_an_input_When_defined_Then_it_posts_json_to_the_scenarios_collection_and_maps_the_created_scenario', async () => {
        const stub = stubFetch(
            jsonResponse(
                {
                    id: 's1',
                    name: 'Aggressive saving',
                    currency: 'ILS',
                    flows: [FLOW_ID],
                    growthRatePerStep: '0.005',
                },
                201,
            ),
        );

        const scenario = await defineScenario({
            name: 'Aggressive saving',
            currency: 'ILS',
            flows: [FLOW_ID],
            growthRatePerStep: '0.005',
        });

        expect(scenario.growthRatePerStep).toBe('0.005');
        const { url, init } = onlyCall(stub);
        expect(url).toBe('/ledger/api/v1/scenarios');
        expect(init.method).toBe('POST');
        expect(JSON.parse(init.body as string)).toEqual({
            name: 'Aggressive saving',
            currency: 'ILS',
            flows: [FLOW_ID],
            growthRatePerStep: '0.005',
        });
    });

    it('Given_no_flows_When_defined_Then_it_posts_an_empty_flows_array_for_the_do_nothing_baseline', async () => {
        const stub = stubFetch(
            jsonResponse(
                { id: 's1', name: 'Baseline', currency: 'ILS', flows: [], growthRatePerStep: '0' },
                201,
            ),
        );

        await defineScenario({
            name: 'Baseline',
            currency: 'ILS',
            flows: [],
            growthRatePerStep: '0',
        });

        const body = JSON.parse(onlyCall(stub).init.body as string) as Record<string, unknown>;
        expect(body.flows).toEqual([]);
    });

    it('Given_a_flow_naming_no_plan_When_defined_Then_it_rejects_with_an_ApiError_coded_UNKNOWN_PLAN', async () => {
        stubFetch(
            problemResponse(
                { status: 422, code: 'UNKNOWN_PLAN', detail: 'No planned flow has this id.' },
                422,
            ),
        );

        await expect(
            defineScenario({
                name: 'Aggressive saving',
                currency: 'ILS',
                flows: ['unknown'],
                growthRatePerStep: '0.005',
            }),
        ).rejects.toMatchObject({ status: 422, problem: { code: 'UNKNOWN_PLAN' } });
    });

    it('Given_a_flow_in_another_currency_When_defined_Then_it_rejects_with_an_ApiError_coded_RULE_VIOLATED', async () => {
        stubFetch(
            problemResponse(
                { status: 422, code: 'RULE_VIOLATED', detail: 'The flow is in another currency.' },
                422,
            ),
        );

        await expect(
            defineScenario({
                name: 'Aggressive saving',
                currency: 'ILS',
                flows: [FLOW_ID],
                growthRatePerStep: '0.005',
            }),
        ).rejects.toMatchObject({ status: 422, problem: { code: 'RULE_VIOLATED' } });
    });
});
