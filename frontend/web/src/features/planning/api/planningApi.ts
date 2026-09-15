/**
 * The planning feature's calls to the ledger, and the web client's anti-corruption edge for them
 * (ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Amounts stay the strings the ledger sent, and `interval`/`count` stay integers -
 * never converted (ADR-028, ADR-029). A planned flow's amount travels positive and is never
 * negated (ADR-015).
 */
import { ApiBodyError, LEDGER_API, requestJson, sendJson } from '@/shared/api/client';
import type {
    BalancePointResponse,
    ComparedScenarioResponse,
    ComparisonResponse,
    DefineScenarioRequest,
    MoneyDto,
    PlanCancelRequest,
    PlanFlowRequest,
    PlannedFlowResponse,
    PlanStateResponse,
    PlanTransitionRequest,
    ProjectionResponse,
    RecurrenceRequest,
    RecurrenceResponse,
    ScenarioResponse,
} from '@/shared/api/contracts/ledger/types.gen';
import { log } from '@/shared/lib/logger';

import type { ComparedScenario, Comparison, ComparisonParams } from '../types/comparison';
import { DIRECTIONS, type PlanFlowInput, type PlannedFlow, type PlanState } from '../types/flow';
import { CURRENCIES, type Currency, type Money } from '../types/money';
import type {
    BalancePoint,
    Projection,
    ProjectionParams,
    ProjectionSettings,
} from '../types/projection';
import { CADENCES, type Cadence, type Recurrence } from '../types/recurrence';
import type { DefineScenarioInput, Scenario } from '../types/scenario';

const PLANNED_FLOWS_URL = `${LEDGER_API}/planned-flows`;
const SCENARIOS_URL = `${LEDGER_API}/scenarios`;
const SCENARIO_COMPARISONS_URL = `${LEDGER_API}/scenario-comparisons`;

/** The URL of one planned flow's lifecycle action, with the id encoded as a single path segment. */
function flowActionUrl(flowId: string, action: string): string {
    return `${PLANNED_FLOWS_URL}/${encodeURIComponent(flowId)}/${action}`;
}

/**
 * A currency the contract does not name.
 *
 * The generated type promises one of five codes, but the body is unvalidated JSON, so this keeps
 * a runtime default rather than trusting it blindly, the same as an unnamed status or kind.
 *
 * @throws ApiBodyError - always.
 */
function unknownCurrency(url: string): never {
    log.error('ledger response has a currency the contract does not name', { url });
    throw new ApiBodyError(url, 'A response arrived with a currency the contract does not name');
}

function toMoney(wire: MoneyDto, url: string): Money {
    if (!CURRENCIES.includes(wire.currency)) {
        return unknownCurrency(url);
    }
    return { amount: wire.amount, currency: wire.currency };
}

/**
 * A member a response's variant always carries, but the contract marks optional because it has
 * no discriminated shape for one kind or status to describe (ADR-028) - mirrors accounting's own
 * `variantMember`, duplicated because a feature imports no other feature.
 *
 * @throws ApiBodyError - when the member is absent.
 */
function requiredMember<Value>(value: Value | undefined, member: string, url: string): Value {
    if (value === undefined) {
        log.error('ledger response lacks a member its variant carries', { url, member });
        throw new ApiBodyError(url, `A response arrived without its ${member}`);
    }
    return value;
}

/**
 * A plan state whose status the contract does not name.
 *
 * The generated type lists four statuses, but the body is unvalidated JSON, so this keeps a
 * runtime default rather than leaving a state `undefined` for a screen to crash on. The status
 * itself is left out of the log line, since it arrived unvalidated.
 *
 * @throws ApiBodyError - always.
 */
function unknownStatus(url: string): never {
    log.error('ledger plan state has a status the contract does not name', { url });
    throw new ApiBodyError(url, 'A plan state arrived with a status the contract does not name');
}

function toPlanState(wire: PlanStateResponse, url: string): PlanState {
    switch (wire.status) {
        case 'PLANNED':
            return { status: 'PLANNED' };
        case 'COMMITTED':
            return { status: 'COMMITTED', on: requiredMember(wire.on, 'on', url) };
        case 'REALISED':
            return { status: 'REALISED', on: requiredMember(wire.on, 'on', url) };
        case 'CANCELLED':
            return {
                status: 'CANCELLED',
                on: requiredMember(wire.on, 'on', url),
                reason: requiredMember(wire.reason, 'reason', url),
            };
        default:
            return unknownStatus(url);
    }
}

/**
 * A recurrence whose kind the contract does not name. See `unknownStatus`.
 *
 * @throws ApiBodyError - always.
 */
function unknownRecurrenceKind(url: string): never {
    log.error('ledger recurrence has a kind the contract does not name', { url });
    throw new ApiBodyError(url, 'A recurrence arrived with a kind the contract does not name');
}

/**
 * A cadence the contract does not name. See `unknownStatus`.
 *
 * @throws ApiBodyError - always.
 */
function unknownCadence(url: string): never {
    log.error('ledger recurrence has a cadence the contract does not name', { url });
    throw new ApiBodyError(url, 'A recurrence arrived with a cadence the contract does not name');
}

/** `value`, required and validated against `CADENCES`. */
function requiredCadence(value: RecurrenceResponse['cadence'], url: string): Cadence {
    const cadence = requiredMember(value, 'cadence', url);
    return CADENCES.includes(cadence) ? cadence : unknownCadence(url);
}

function toRecurrence(wire: RecurrenceResponse, url: string): Recurrence {
    switch (wire.kind) {
        case 'ONCE':
            return { kind: 'ONCE' };
        case 'UNTIL':
            return {
                kind: 'UNTIL',
                cadence: requiredCadence(wire.cadence, url),
                interval: requiredMember(wire.interval, 'interval', url),
                until: requiredMember(wire.until, 'until', url),
            };
        case 'TIMES':
            return {
                kind: 'TIMES',
                cadence: requiredCadence(wire.cadence, url),
                interval: requiredMember(wire.interval, 'interval', url),
                count: requiredMember(wire.count, 'count', url),
            };
        default:
            return unknownRecurrenceKind(url);
    }
}

/** `recurrence`, mapped to the wire shape with only the members its `kind` carries present. */
function toRecurrenceRequest(recurrence: Recurrence): RecurrenceRequest {
    switch (recurrence.kind) {
        case 'ONCE':
            return { kind: 'ONCE' };
        case 'UNTIL':
            return {
                kind: 'UNTIL',
                cadence: recurrence.cadence,
                interval: recurrence.interval,
                until: recurrence.until,
            };
        case 'TIMES':
            return {
                kind: 'TIMES',
                cadence: recurrence.cadence,
                interval: recurrence.interval,
                count: recurrence.count,
            };
    }
}

/**
 * A direction the contract does not name.
 *
 * @throws ApiBodyError - always.
 */
function unknownDirection(url: string): never {
    log.error('ledger planned flow has a direction the contract does not name', { url });
    throw new ApiBodyError(
        url,
        'A planned flow arrived with a direction the contract does not name',
    );
}

function toPlannedFlow(wire: PlannedFlowResponse, url: string): PlannedFlow {
    return {
        id: wire.id,
        description: wire.description,
        amount: toMoney(wire.amount, url),
        direction: DIRECTIONS.includes(wire.direction) ? wire.direction : unknownDirection(url),
        startsOn: wire.startsOn,
        recurrence: toRecurrence(wire.recurrence, url),
        state: toPlanState(wire.state, url),
    };
}

function toScenario(wire: ScenarioResponse, url: string): Scenario {
    return {
        id: wire.id,
        name: wire.name,
        currency: CURRENCIES.includes(wire.currency) ? wire.currency : unknownCurrency(url),
        flows: wire.flows,
        growthRatePerStep: wire.growthRatePerStep,
    };
}

/** `value`, validated against `CURRENCIES`. */
function knownCurrency(value: Currency, url: string): Currency {
    return CURRENCIES.includes(value) ? value : unknownCurrency(url);
}

/**
 * A projection or comparison step the contract does not name. See `unknownStatus`.
 *
 * @throws ApiBodyError - always.
 */
function unknownStep(url: string): never {
    log.error('ledger response has a step the contract does not name', { url });
    throw new ApiBodyError(url, 'A response arrived with a step the contract does not name');
}

/** `value`, validated against `CADENCES`: a projection steps at the same cadences a flow repeats at. */
function knownStep(value: Cadence, url: string): Cadence {
    return CADENCES.includes(value) ? value : unknownStep(url);
}

function toBalancePoint(wire: BalancePointResponse, url: string): BalancePoint {
    return {
        date: wire.date,
        cash: toMoney(wire.cash, url),
        invested: toMoney(wire.invested, url),
        total: toMoney(wire.total, url),
    };
}

/**
 * `wire` mapped point by point, refusing an empty series: the ledger always includes both ends of
 * the horizon, so none at all means it broke its own contract, and a chart has nothing to scale.
 *
 * @throws ApiBodyError - when `wire` is empty.
 */
function toPoints(
    wire: readonly BalancePointResponse[],
    url: string,
): readonly [BalancePoint, ...BalancePoint[]] {
    const [first, ...rest] = wire;
    if (first === undefined) {
        log.error('ledger projection has no points', { url });
        throw new ApiBodyError(url, 'A projection arrived without any points');
    }
    return [toBalancePoint(first, url), ...rest.map((point) => toBalancePoint(point, url))];
}

function toProjection(wire: ProjectionResponse, url: string): Projection {
    return {
        scenario: wire.scenario,
        horizon: { from: wire.horizon.from, to: wire.horizon.to },
        step: knownStep(wire.step, url),
        currency: knownCurrency(wire.currency, url),
        points: toPoints(wire.points, url),
        ...(wire.firstShortfall === undefined ? {} : { firstShortfall: wire.firstShortfall }),
    };
}

function toComparedScenario(wire: ComparedScenarioResponse, url: string): ComparedScenario {
    return {
        id: wire.id,
        name: wire.name,
        points: wire.points.map((point) => toBalancePoint(point, url)),
        endState: toBalancePoint(wire.endState, url),
        // A divergence has a balance point's shape, its amounts signed differences instead.
        divergence: wire.divergence.map((difference) => toBalancePoint(difference, url)),
    };
}

/**
 * `wire` mapped, its baseline resolved to the compared scenario carrying that id.
 *
 * @throws ApiBodyError - when no compared scenario carries the baseline's id: every divergence is
 *   measured from it, so a comparison without it has nothing to show them against.
 */
function toComparison(wire: ComparisonResponse, url: string): Comparison {
    const scenarios = wire.scenarios.map((scenario) => toComparedScenario(scenario, url));
    const baseline = scenarios.find((scenario) => scenario.id === wire.baseline);
    if (baseline === undefined) {
        log.error('ledger comparison has a baseline that is none of its scenarios', { url });
        throw new ApiBodyError(
            url,
            'A comparison arrived whose baseline is not among its scenarios',
        );
    }
    return {
        horizon: { from: wire.horizon.from, to: wire.horizon.to },
        step: knownStep(wire.step, url),
        currency: knownCurrency(wire.currency, url),
        baseline,
        scenarios,
    };
}

/**
 * Append `settings` to `query`: the horizon and the step, then one `cash` and one `invested`
 * parameter per account, appended rather than joined, as the ledger reads a repeated parameter.
 * Only ids, dates and the step travel in the URL - never an amount (ADR-028).
 */
function appendSettings(query: URLSearchParams, settings: ProjectionSettings): void {
    query.append('from', settings.from);
    query.append('to', settings.to);
    query.append('step', settings.step);
    for (const ref of settings.cash) {
        query.append('cash', ref);
    }
    for (const ref of settings.invested) {
        query.append('invested', ref);
    }
}

/**
 * Every planned flow, in the order the ledger lists them (by start date) - every state, a
 * cancelled flow included.
 *
 * @returns the flows, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body, a state or recurrence lacks a
 *   member its variant always carries, or names a status or kind the contract does not name.
 */
export async function listPlannedFlows(): Promise<PlannedFlow[]> {
    const wire = await requestJson<PlannedFlowResponse[]>(PLANNED_FLOWS_URL);
    return wire.map((flow) => toPlannedFlow(flow, PLANNED_FLOWS_URL));
}

/**
 * Plan a flow: an intention, not a fact (ADR-015). The ledger generates its id and the flow
 * starts `PLANNED`.
 *
 * @param input - the description, amount, direction, start date and recurrence. The amount
 *   travels exactly as given, positive, and is never negated here.
 * @returns the flow as planned.
 * @throws ApiError - on a non-2xx response: 400 `MALFORMED_REQUEST`, possibly listing field
 *   `errors` (an incomplete recurrence among them, as `recurrence.completeForKind`), 422
 *   `RULE_VIOLATED` for a rule the domain refuses, such as a non-positive amount.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function planFlow(input: PlanFlowInput): Promise<PlannedFlow> {
    const body: PlanFlowRequest = {
        description: input.description,
        amount: { amount: input.amount.amount, currency: input.amount.currency },
        direction: input.direction,
        startsOn: input.startsOn,
        recurrence: toRecurrenceRequest(input.recurrence),
    };
    const wire = await sendJson<PlannedFlowResponse>(PLANNED_FLOWS_URL, 'POST', body);
    return toPlannedFlow(wire, PLANNED_FLOWS_URL);
}

/**
 * Commit to a planned flow, on the given date.
 *
 * @param flowId - the flow's id.
 * @param on - the ISO `YYYY-MM-DD` commitment date.
 * @returns the flow in its new, `COMMITTED` state.
 * @throws ApiError - on a non-2xx response: 404 `PLAN_NOT_FOUND`, 409 `ILLEGAL_TRANSITION` when
 *   the flow is not `PLANNED`, 422 `RULE_VIOLATED`.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function commitFlow(flowId: string, on: string): Promise<PlannedFlow> {
    const url = flowActionUrl(flowId, 'commit');
    const body: PlanTransitionRequest = { on };
    const wire = await sendJson<PlannedFlowResponse>(url, 'POST', body);
    return toPlannedFlow(wire, url);
}

/**
 * Realise a committed flow: the money has moved, on the given date.
 *
 * @param flowId - the flow's id.
 * @param on - the ISO `YYYY-MM-DD` realisation date.
 * @returns the flow in its new, `REALISED` state.
 * @throws ApiError - on a non-2xx response: 404 `PLAN_NOT_FOUND`, 409 `ILLEGAL_TRANSITION` when
 *   the flow is not `COMMITTED`, 422 `RULE_VIOLATED`.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function realiseFlow(flowId: string, on: string): Promise<PlannedFlow> {
    const url = flowActionUrl(flowId, 'realise');
    const body: PlanTransitionRequest = { on };
    const wire = await sendJson<PlannedFlowResponse>(url, 'POST', body);
    return toPlannedFlow(wire, url);
}

/**
 * Cancel a planned or committed flow, on the given date, for the given reason.
 *
 * @param flowId - the flow's id.
 * @param on - the ISO `YYYY-MM-DD` cancellation date.
 * @param reason - why it is cancelled, kept on the record.
 * @returns the flow in its new, `CANCELLED` state.
 * @throws ApiError - on a non-2xx response: 404 `PLAN_NOT_FOUND`, 409 `ILLEGAL_TRANSITION` when
 *   the flow is already `REALISED` or `CANCELLED`, 422 `RULE_VIOLATED`.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function cancelFlow(flowId: string, on: string, reason: string): Promise<PlannedFlow> {
    const url = flowActionUrl(flowId, 'cancel');
    const body: PlanCancelRequest = { on, reason };
    const wire = await sendJson<PlannedFlowResponse>(url, 'POST', body);
    return toPlannedFlow(wire, url);
}

/**
 * Every scenario, in the order the ledger lists them (by name).
 *
 * @returns the scenarios, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function listScenarios(): Promise<Scenario[]> {
    const wire = await requestJson<ScenarioResponse[]>(SCENARIOS_URL);
    return wire.map((scenario) => toScenario(scenario, SCENARIOS_URL));
}

/**
 * Define a named scenario: a set of planned flows plus a growth assumption. The ledger generates
 * its id. `flows` may be empty - the do-nothing baseline is a scenario with no flows.
 *
 * @param input - the name, currency, flow ids (each already in `currency`) and the per-step
 *   growth rate, sent exactly as given.
 * @returns the scenario as defined.
 * @throws ApiError - on a non-2xx response: 422 `UNKNOWN_PLAN` for a flow id naming no plan, 422
 *   `RULE_VIOLATED` for a flow in another currency or listed twice, 400 `MALFORMED_REQUEST`,
 *   possibly listing field `errors`.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function defineScenario(input: DefineScenarioInput): Promise<Scenario> {
    const body: DefineScenarioRequest = {
        name: input.name,
        currency: input.currency,
        flows: [...input.flows],
        growthRatePerStep: input.growthRatePerStep,
    };
    const wire = await sendJson<ScenarioResponse>(SCENARIOS_URL, 'POST', body);
    return toScenario(wire, SCENARIOS_URL);
}

/**
 * Project a scenario across a horizon, opening from the accounts named. Side-effect free: nothing
 * is recorded, and the ledger re-reads the scenario's flows on every call (ADR-016).
 *
 * @param params - the scenario, the horizon, the step, and the account refs opening each line.
 * @returns the projection, every amount the string the ledger sent.
 * @throws ApiError - on a non-2xx response: 404 `SCENARIO_NOT_FOUND`, 422 `RULE_VIOLATED` for a
 *   horizon that is not whole steps or does not start before it ends, or an account ref that is
 *   unknown or in another currency.
 * @throws ApiBodyError - when the response carries no JSON body, no points, or a step or currency
 *   the contract does not name.
 */
export async function projectScenario(params: ProjectionParams): Promise<Projection> {
    const query = new URLSearchParams();
    appendSettings(query, params);
    const url = `${SCENARIOS_URL}/${encodeURIComponent(params.scenarioId)}/projection?${query.toString()}`;
    const wire = await requestJson<ProjectionResponse>(url);
    return toProjection(wire, url);
}

/**
 * Compare two or more scenarios over one horizon, one step and one opening position, measuring
 * each from a baseline. Side-effect free: nothing is recorded (ADR-016).
 *
 * @param params - the scenario ids in the order to list them, the baseline among them, the
 *   horizon, the step, and the account refs opening each line.
 * @returns the comparison, its scenarios in the order requested, every amount - divergences
 *   included - the signed string the ledger sent.
 * @throws ApiError - on a non-2xx response: 400 when fewer than two scenarios are named, 422
 *   `UNKNOWN_SCENARIO` for an id naming no scenario, and 422 `RULE_VIOLATED` for a baseline not
 *   among them, scenarios in different currencies, or a horizon or account ref it refuses.
 * @throws ApiBodyError - when the response carries no JSON body, a baseline that is none of its
 *   scenarios, or a step or currency the contract does not name.
 */
export async function compareScenarios(params: ComparisonParams): Promise<Comparison> {
    const query = new URLSearchParams();
    for (const scenarioId of params.scenarioIds) {
        query.append('scenario', scenarioId);
    }
    query.append('baseline', params.baselineId);
    appendSettings(query, params);
    const url = `${SCENARIO_COMPARISONS_URL}?${query.toString()}`;
    const wire = await requestJson<ComparisonResponse>(url);
    return toComparison(wire, url);
}
