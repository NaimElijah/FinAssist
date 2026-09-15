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
    DefineScenarioRequest,
    MoneyDto,
    PlanCancelRequest,
    PlanFlowRequest,
    PlannedFlowResponse,
    PlanStateResponse,
    PlanTransitionRequest,
    RecurrenceRequest,
    RecurrenceResponse,
    ScenarioResponse,
} from '@/shared/api/contracts/ledger/types.gen';
import { log } from '@/shared/lib/logger';

import type { PlanFlowInput, PlannedFlow, PlanState } from '../types/flow';
import type { Money } from '../types/money';
import type { Recurrence } from '../types/recurrence';
import type { DefineScenarioInput, Scenario } from '../types/scenario';

const PLANNED_FLOWS_URL = `${LEDGER_API}/planned-flows`;
const SCENARIOS_URL = `${LEDGER_API}/scenarios`;

/** The URL of one planned flow's lifecycle action, with the id encoded as a single path segment. */
function flowActionUrl(flowId: string, action: string): string {
    return `${PLANNED_FLOWS_URL}/${encodeURIComponent(flowId)}/${action}`;
}

function toMoney(wire: MoneyDto): Money {
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

function toRecurrence(wire: RecurrenceResponse, url: string): Recurrence {
    switch (wire.kind) {
        case 'ONCE':
            return { kind: 'ONCE' };
        case 'UNTIL':
            return {
                kind: 'UNTIL',
                cadence: requiredMember(wire.cadence, 'cadence', url),
                interval: requiredMember(wire.interval, 'interval', url),
                until: requiredMember(wire.until, 'until', url),
            };
        case 'TIMES':
            return {
                kind: 'TIMES',
                cadence: requiredMember(wire.cadence, 'cadence', url),
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

function toPlannedFlow(wire: PlannedFlowResponse, url: string): PlannedFlow {
    return {
        id: wire.id,
        description: wire.description,
        amount: toMoney(wire.amount),
        direction: wire.direction,
        startsOn: wire.startsOn,
        recurrence: toRecurrence(wire.recurrence, url),
        state: toPlanState(wire.state, url),
    };
}

function toScenario(wire: ScenarioResponse): Scenario {
    return {
        id: wire.id,
        name: wire.name,
        currency: wire.currency,
        flows: wire.flows,
        growthRatePerStep: wire.growthRatePerStep,
    };
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
    return wire.map(toScenario);
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
    return toScenario(wire);
}
