import { Link, useParams } from 'react-router';

import { ApiError } from '@/shared/api/client';
import { useDocumentTitle } from '@/shared/lib/documentTitle';
import { type Column, DataTable, EmptyState, MoneyText, StateBadge, type Tone } from '@/shared/ui';

import { BUDGET_NOT_FOUND } from '../api/budgetingApi';
import { useBudgetReview, useBudgets } from '../hooks/budgetQueries';
import type { Budget } from '../types/budget';
import { periodLabel } from '../types/period';
import type { BudgetReview, CategorySpend, UnbudgetedSpend } from '../types/review';
import styles from './BudgetReviewPage.module.css';
import { BUDGETS_PATH } from './paths';
import { PeriodLabel } from './PeriodLabel';
import { QueryFailure } from './QueryFailure';

const NO_UNBUDGETED_MESSAGE = 'No unbudgeted spend';

/** Whether a category is overspent, for `StateBadge`. The text carries the meaning, not colour. */
type SpendStatus = 'OVERSPENT' | 'WITHIN_LIMIT';

const SPEND_STATUS_TONES: Readonly<Record<SpendStatus, Tone>> = {
    OVERSPENT: 'negative',
    WITHIN_LIMIT: 'positive',
};

const SPEND_STATUS_LABELS: Readonly<Record<SpendStatus, string>> = {
    OVERSPENT: 'Overspent',
    WITHIN_LIMIT: 'Within limit',
};

function spendStatus(overspent: boolean): SpendStatus {
    return overspent ? 'OVERSPENT' : 'WITHIN_LIMIT';
}

/** Whether a read failed because the ledger holds no budget with the id in its path. */
function isBudgetNotFound(error: Error | null): boolean {
    return error instanceof ApiError && error.problem?.code === BUDGET_NOT_FOUND;
}

function BackToBudgets() {
    return <Link to={BUDGETS_PATH}>Back to Budgets</Link>;
}

function BudgetNotFound() {
    return (
        <>
            <h1>Budget not found</h1>
            <EmptyState
                message="The ledger holds no budget at this address."
                action={<BackToBudgets />}
            />
        </>
    );
}

const CATEGORY_COLUMNS: readonly Column<CategorySpend>[] = [
    { key: 'category', header: 'Category', rowHeader: true, render: (row) => row.category },
    {
        key: 'limit',
        header: 'Limit',
        align: 'end',
        render: (row) => <MoneyText amount={row.limit.amount} currency={row.limit.currency} />,
    },
    {
        key: 'spent',
        header: 'Spent',
        align: 'end',
        render: (row) => <MoneyText amount={row.spent.amount} currency={row.spent.currency} />,
    },
    {
        key: 'remaining',
        header: 'Remaining',
        align: 'end',
        // May be negative; rendered exactly as the ledger sent it, never recomputed (ADR-029).
        render: (row) => (
            <MoneyText amount={row.remaining.amount} currency={row.remaining.currency} />
        ),
    },
    {
        key: 'status',
        header: 'Status',
        render: (row) => (
            <StateBadge
                status={spendStatus(row.overspent)}
                tones={SPEND_STATUS_TONES}
                label={SPEND_STATUS_LABELS[spendStatus(row.overspent)]}
            />
        ),
    },
];

const UNBUDGETED_COLUMNS: readonly Column<UnbudgetedSpend>[] = [
    { key: 'category', header: 'Category', rowHeader: true, render: (row) => row.category },
    {
        key: 'spent',
        header: 'Spent',
        align: 'end',
        render: (row) => <MoneyText amount={row.spent.amount} currency={row.spent.currency} />,
    },
];

/** The categories, unbudgeted and uncategorised sections, once the review has loaded. */
function ReviewSections({ review }: { readonly review: BudgetReview }) {
    return (
        <>
            <section className={styles.section}>
                <h2 className={styles.heading}>Spend by category</h2>
                <DataTable
                    caption="Spend by category"
                    captionHidden
                    columns={CATEGORY_COLUMNS}
                    rows={review.categories}
                    rowKey={(row) => row.category}
                />
            </section>
            <section className={styles.section}>
                <h2 className={styles.heading}>Unbudgeted spend</h2>
                <p className={styles.hint}>
                    Spend that matched a rule for a category this budget sets no limit for.
                </p>
                {review.unbudgeted.length === 0 ? (
                    <EmptyState message={NO_UNBUDGETED_MESSAGE} />
                ) : (
                    <DataTable
                        caption="Unbudgeted spend"
                        captionHidden
                        columns={UNBUDGETED_COLUMNS}
                        rows={review.unbudgeted}
                        rowKey={(row) => row.category}
                    />
                )}
            </section>
            <section className={styles.section}>
                <h2 className={styles.heading}>Uncategorised</h2>
                <p className={styles.uncategorised}>
                    <MoneyText
                        amount={review.uncategorised.amount}
                        currency={review.uncategorised.currency}
                    />
                </p>
                <p className={styles.hint}>
                    Spend that matched no rule, which means the rules need work rather than the
                    spending.
                </p>
            </section>
        </>
    );
}

/**
 * One budget's page, for an id taken from the URL.
 *
 * The budget itself - its period and currency - comes from the cached budgets list, because the
 * ledger has no single-item read (ADR-028); the review, derived fresh, is its own query. Not
 * found when the review answers 404 `BUDGET_NOT_FOUND`, or when the loaded list does not hold the
 * id.
 *
 * The document is titled after the budget's period, such as "September 2026 review", only while
 * the page shows it. While the budget loads, or when it is not found, the route's own title
 * stands.
 */
function BudgetDetail({ budgetId }: { readonly budgetId: string }) {
    const budgets = useBudgets();
    const review = useBudgetReview(budgetId);
    const notFound = isBudgetNotFound(review.error);
    const budget = budgets.data?.find((candidate: Budget) => candidate.id === budgetId);
    // Called before any early return, as every hook must be.
    useDocumentTitle(
        notFound || budget === undefined ? undefined : `${periodLabel(budget.period)} review`,
    );

    if (notFound) {
        return <BudgetNotFound />;
    }
    const budgetsFailure = budgets.isPending ? null : <QueryFailure query={budgets} />;
    if (budgets.data === undefined) {
        return (
            <>
                <p className={styles.back}>
                    <BackToBudgets />
                </p>
                <h1>Budget review</h1>
                {budgets.isPending ? (
                    <p className={styles.placeholder}>Loading budget…</p>
                ) : (
                    budgetsFailure
                )}
            </>
        );
    }
    if (budget === undefined) {
        return <BudgetNotFound />;
    }

    return (
        <>
            <p className={styles.back}>
                <BackToBudgets />
            </p>
            {budgetsFailure}
            <header className={styles.header}>
                <h1 className={styles.name}>
                    <PeriodLabel period={budget.period} />
                </h1>
                <p className={styles.meta}>{budget.currency}</p>
            </header>
            {review.isPending ? (
                <p className={styles.placeholder}>Loading review…</p>
            ) : (
                <div className={styles.stack}>
                    <QueryFailure query={review} />
                    {review.data === undefined ? null : <ReviewSections review={review.data} />}
                </div>
            )}
        </>
    );
}

/**
 * `/budgets/:budgetId`: a budget's period and currency, then how it is doing - spend by category
 * with each one's status, unbudgeted spend, and spend that matched no rule at all.
 */
export function BudgetReviewPage() {
    const { budgetId } = useParams();
    return budgetId === undefined ? <BudgetNotFound /> : <BudgetDetail budgetId={budgetId} />;
}
