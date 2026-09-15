/**
 * The budgeting feature's calls to the ledger, and the web client's anti-corruption edge for them
 * (ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Decimal amounts stay the strings the ledger sent (ADR-028).
 */
import { LEDGER_API, requestJson, sendJson } from '@/shared/api/client';
import type {
    BudgetPeriodDto,
    BudgetResponse,
    CategoryLimitDto,
    DefineBudgetRequest,
    MoneyDto,
} from '@/shared/api/contracts/ledger/types.gen';

import type { Budget, CategoryLimit, DefineBudgetInput } from '../types/budget';
import type { Money } from '../types/money';
import type { BudgetPeriod } from '../types/period';

const BUDGETS_URL = `${LEDGER_API}/budgets`;

function toMoney(wire: MoneyDto): Money {
    return { amount: wire.amount, currency: wire.currency };
}

function toPeriod(wire: BudgetPeriodDto): BudgetPeriod {
    return { start: wire.start, end: wire.end };
}

function toCategoryLimit(wire: CategoryLimitDto): CategoryLimit {
    return { category: wire.category, limit: toMoney(wire.limit) };
}

function toBudget(wire: BudgetResponse): Budget {
    return {
        id: wire.id,
        period: toPeriod(wire.period),
        currency: wire.currency,
        limits: wire.limits.map(toCategoryLimit),
        total: toMoney(wire.total),
    };
}

/**
 * Every budget, period start descending, in the order the ledger lists them.
 *
 * @returns the budgets, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function listBudgets(): Promise<Budget[]> {
    const wire = await requestJson<BudgetResponse[]>(BUDGETS_URL);
    return wire.map(toBudget);
}

/**
 * Define a budget for a period, with a limit per category. The ledger generates its id.
 *
 * @param input - the period, currency and per-category limits, each limit already in the
 *   budget's currency.
 * @returns the budget as defined, its limits sorted by category and its total derived.
 * @throws ApiError - on a non-2xx response: 422 `RULE_VIOLATED` for a rule the domain refuses -
 *   no limits, a duplicate category, a non-positive limit, a limit in another currency, or a
 *   period that does not start before it ends - 400 `MALFORMED_REQUEST`, possibly listing field
 *   `errors`, for a request the ledger cannot read.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function defineBudget(input: DefineBudgetInput): Promise<Budget> {
    const body: DefineBudgetRequest = {
        period: { start: input.period.start, end: input.period.end },
        currency: input.currency,
        limits: input.limits.map((limit) => ({
            category: limit.category,
            limit: { amount: limit.limit.amount, currency: limit.limit.currency },
        })),
    };
    const wire = await sendJson<BudgetResponse>(BUDGETS_URL, 'POST', body);
    return toBudget(wire);
}
