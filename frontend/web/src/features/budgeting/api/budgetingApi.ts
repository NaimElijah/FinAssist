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
    BudgetReviewResponse,
    CategoryLimitDto,
    CategorySpendResponse,
    DefineBudgetRequest,
    MoneyDto,
    UnbudgetedSpendResponse,
} from '@/shared/api/contracts/ledger/types.gen';

import type { Budget, CategoryLimit, DefineBudgetInput } from '../types/budget';
import type { Money } from '../types/money';
import type { BudgetPeriod } from '../types/period';
import type { BudgetReview, CategorySpend, UnbudgetedSpend } from '../types/review';

/** The ledger's code for a budget id in the path that names no budget (ADR-028: 404). */
export const BUDGET_NOT_FOUND = 'BUDGET_NOT_FOUND';

const BUDGETS_URL = `${LEDGER_API}/budgets`;

/** The URL of one budget's sub-resource, with the id encoded as a single path segment. */
function budgetUrl(budgetId: string, subResource: string): string {
    return `${BUDGETS_URL}/${encodeURIComponent(budgetId)}/${subResource}`;
}

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

function toCategorySpend(wire: CategorySpendResponse): CategorySpend {
    return {
        category: wire.category,
        limit: toMoney(wire.limit),
        spent: toMoney(wire.spent),
        remaining: toMoney(wire.remaining),
        overspent: wire.overspent,
    };
}

function toUnbudgetedSpend(wire: UnbudgetedSpendResponse): UnbudgetedSpend {
    return { category: wire.category, spent: toMoney(wire.spent) };
}

function toBudgetReview(wire: BudgetReviewResponse): BudgetReview {
    return {
        budget: wire.budget,
        period: toPeriod(wire.period),
        currency: wire.currency,
        categories: wire.categories.map(toCategorySpend),
        unbudgeted: wire.unbudgeted.map(toUnbudgetedSpend),
        uncategorised: toMoney(wire.uncategorised),
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

/**
 * How a budget is doing, derived fresh by the ledger on every call.
 *
 * @param budgetId - the budget's id.
 * @returns the review: every budgeted category's limit, spend and remaining, spend unbudgeted
 *   for a category with no limit, and spend that matched no rule at all.
 * @throws ApiError - on a non-2xx response: 404 `BUDGET_NOT_FOUND` for an unknown budget.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function reviewBudget(budgetId: string): Promise<BudgetReview> {
    const wire = await requestJson<BudgetReviewResponse>(budgetUrl(budgetId, 'review'));
    return toBudgetReview(wire);
}
