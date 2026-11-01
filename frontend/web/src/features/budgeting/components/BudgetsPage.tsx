import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';

import { useWriteAccess } from '@/shared/lib/writeAccess';
import {
    type Column,
    DataTable,
    EmptyState,
    LoadingNote,
    MoneyText,
    QueryErrorState,
    useRetryFocus,
} from '@/shared/ui';

import { useBudgets } from '../hooks/budgetQueries';
import type { Budget } from '../types/budget';
import { periodLabel } from '../types/period';
import styles from './BudgetsPage.module.css';
import { DefineBudgetForm } from './DefineBudgetForm';
import { budgetReviewPath, CATEGORISATION_RULES_PATH } from './paths';
import { PeriodLabel } from './PeriodLabel';

const EMPTY_MESSAGE = 'No budgets yet — define one below';

/** The empty list for a viewer, who is offered no form to define one with (ADR-032). */
const READ_ONLY_EMPTY_MESSAGE = 'No budgets yet';

/** A budget's "Review" link, taking focus once when it was just defined. */
function ReviewLink({
    budget,
    focused,
    onFocused,
}: {
    readonly budget: Budget;
    readonly focused: boolean;
    /** Called once focus has been applied, so the request to focus can be cleared. */
    readonly onFocused: () => void;
}) {
    const ref = useRef<HTMLAnchorElement>(null);

    // Keyed on `focused` rather than run on mount: the new row may render before or after the
    // form reports the budget defined, and either order must end with focus here. Reporting back
    // clears the request, so a later remount of this row cannot take focus a second time.
    useEffect(() => {
        if (focused) {
            ref.current?.focus();
            onFocused();
        }
    }, [focused, onFocused]);

    return (
        <Link
            ref={ref}
            to={budgetReviewPath(budget.id)}
            // The row's period and currency say which budget it opens out of the table's context,
            // after the visible word so a voice command naming it still matches (WCAG 2.5.3). Two
            // budgets sharing both share the name.
            aria-label={`Review ${periodLabel(budget.period)}, ${budget.currency}`}
        >
            Review
        </Link>
    );
}

/** The table's columns. The period heads each row, and focus goes to the link just defined. */
function budgetColumns(
    focusedBudgetId: string | undefined,
    onFocused: () => void,
): readonly Column<Budget>[] {
    return [
        {
            key: 'period',
            header: 'Period',
            rowHeader: true,
            render: (budget) => <PeriodLabel period={budget.period} />,
        },
        { key: 'currency', header: 'Currency', render: (budget) => budget.currency },
        {
            key: 'total',
            header: 'Total',
            align: 'end',
            render: (budget) => (
                <MoneyText amount={budget.total.amount} currency={budget.total.currency} />
            ),
        },
        {
            key: 'review',
            header: 'Review',
            render: (budget) => (
                <ReviewLink
                    budget={budget}
                    focused={budget.id === focusedBudgetId}
                    onFocused={onFocused}
                />
            ),
        },
    ];
}

/**
 * The budgets list in whichever state its request is in.
 *
 * Budgets already on screen stay there when a later refetch fails: TanStack Query keeps the data
 * it holds, so the failure and its retry are shown above the rows rather than in their place.
 */
function BudgetsList({
    focusedBudgetId,
    onFocused,
}: {
    readonly focusedBudgetId: string | undefined;
    readonly onFocused: () => void;
}) {
    const budgets = useBudgets();
    const { canWrite } = useWriteAccess();
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        budgets.error,
        budgets.isFetching,
        () => {
            void budgets.refetch();
        },
    );

    if (budgets.isPending) {
        return <LoadingNote message="Loading budgets…" />;
    }
    const failure = (
        <QueryErrorState error={budgets.error} retrying={budgets.isFetching} onRetry={onRetry} />
    );
    if (budgets.data === undefined) {
        return failure;
    }
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.stack}>
            {failure}
            {budgets.data.length === 0 ? (
                <EmptyState message={canWrite ? EMPTY_MESSAGE : READ_ONLY_EMPTY_MESSAGE} />
            ) : (
                <DataTable
                    caption="Budgets"
                    captionHidden
                    columns={budgetColumns(focusedBudgetId, onFocused)}
                    rows={budgets.data}
                    rowKey={(budget) => budget.id}
                />
            )}
        </div>
    );
}

/**
 * `/budgets`: every budget with its period, currency and total, in the ledger's order, and, for a
 * session with write access, the form that defines another.
 *
 * Defining a budget resets the form and moves focus to the new budget's "Review" link in the
 * list, once - the one success signal, mirroring `AccountsPage`'s own pattern.
 *
 * The link to `CategorisationRulesPage` stays offered to a read-only session (`useWriteAccess`):
 * that screen still has something to read, its own write control hidden the same way (ADR-032).
 */
export function BudgetsPage() {
    const { canWrite } = useWriteAccess();
    const [definedBudgetId, setDefinedBudgetId] = useState<string | undefined>(undefined);
    // Stable, so the link's focus effect does not run again on every render of the page.
    const clearDefinedBudget = useCallback(() => {
        setDefinedBudgetId(undefined);
    }, []);

    return (
        <>
            <h1>Budgets</h1>
            <p className={styles.actions}>
                <Link to={CATEGORISATION_RULES_PATH}>Categorisation rules</Link>
            </p>
            <BudgetsList focusedBudgetId={definedBudgetId} onFocused={clearDefinedBudget} />
            {canWrite ? (
                <DefineBudgetForm
                    onDefined={(budget) => {
                        setDefinedBudgetId(budget.id);
                    }}
                />
            ) : null}
        </>
    );
}
