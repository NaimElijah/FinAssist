import { useCallback, useEffect, useRef, useState } from 'react';

import { EmptyState } from '@/shared/ui';

import { useCategorisationRules } from '../hooks/budgetQueries';
import styles from './CategorisationRulesPage.module.css';
import { AddCategorisationRuleForm } from './AddCategorisationRuleForm';
import { QueryFailure } from './QueryFailure';

const HINT = 'Rules are tried in the order they were added; the first match wins.';

const EMPTY_MESSAGE = 'No rules yet — spend stays uncategorised until one matches';

/**
 * Every rule, in the order they are tried, as an ordered list.
 *
 * Rules already on screen stay there when a later refetch fails: TanStack Query keeps the data it
 * holds, so the failure and its retry are shown above the list rather than in its place. The last
 * item takes focus once, after a rule was just added - the ledger appends every new rule, so the
 * last item is always the one just added, even though a rule carries no id of its own to key on.
 *
 * `onAdded` reports whether that focus was actually delivered: if the refetch the add triggered
 * failed, the list still holds its stale (pre-add) data, and the "last item" would not be the
 * rule just added at all - landing focus there would be silently wrong rather than merely absent,
 * so the caller is told to send focus elsewhere instead.
 */
function RulesList({
    awaitingFocus,
    onAdded,
}: {
    readonly awaitingFocus: boolean;
    /** Called once the add has settled: `true` when focus landed on the new rule, `false` when
     * the refetch failed and no rule could be identified as the new one. */
    readonly onAdded: (landedOnNewRule: boolean) => void;
}) {
    const rules = useCategorisationRules();
    const lastItemRef = useRef<HTMLLIElement>(null);

    useEffect(() => {
        if (!awaitingFocus) {
            return;
        }
        if (rules.isError) {
            onAdded(false);
            return;
        }
        if (rules.data !== undefined && rules.data.length > 0) {
            lastItemRef.current?.focus();
            onAdded(true);
        }
    }, [awaitingFocus, rules.data, rules.isError, onAdded]);

    if (rules.isPending) {
        return <p className={styles.loading}>Loading rules…</p>;
    }
    const failure = <QueryFailure query={rules} />;
    if (rules.data === undefined) {
        return failure;
    }
    return (
        <div className={styles.stack}>
            {failure}
            {rules.data.length === 0 ? (
                <EmptyState message={EMPTY_MESSAGE} />
            ) : (
                <ol className={styles.list}>
                    {rules.data.map((rule, index) => {
                        const isLast = index === rules.data.length - 1;
                        return (
                            // A rule has no id (ADR-028); the pair plus its position is unique
                            // enough for React's reconciliation, since rules are only ever
                            // appended, never reordered or removed. One text node, not a nested
                            // element around the fragment: Testing Library's default text
                            // matcher joins only an element's own direct text-node children, so
                            // splitting this across elements would make the line unfindable by
                            // its full text.
                            <li
                                key={`${rule.fragment}-${rule.category}-${String(index)}`}
                                ref={isLast ? lastItemRef : undefined}
                                tabIndex={isLast ? -1 : undefined}
                            >
                                {`"${rule.fragment}" → ${rule.category}`}
                            </li>
                        );
                    })}
                </ol>
            )}
        </div>
    );
}

/**
 * `/budgets/rules`: every categorisation rule, in the order it is tried, and the form that adds
 * another.
 *
 * Adding a rule resets the form and moves focus to the new rule's list item, once - the one
 * success signal, mirroring `AccountsPage` and `BudgetsPage`'s own pattern. When the refetch the
 * add triggered fails, `RulesList` cannot tell which item is the new rule, so focus falls back to
 * the add form's own first field instead of landing on a stale row.
 */
export function CategorisationRulesPage() {
    const [awaitingFocus, setAwaitingFocus] = useState(false);
    const [fallbackFocusRequested, setFallbackFocusRequested] = useState(false);

    // Stable, so the list's focus effect does not run again on every render of the page.
    const handleAdded = useCallback((landedOnNewRule: boolean) => {
        setAwaitingFocus(false);
        if (!landedOnNewRule) {
            setFallbackFocusRequested(true);
        }
    }, []);
    const clearFallbackFocus = useCallback(() => {
        setFallbackFocusRequested(false);
    }, []);

    return (
        <>
            <h1>Categorisation rules</h1>
            <p className={styles.hint}>{HINT}</p>
            <RulesList awaitingFocus={awaitingFocus} onAdded={handleAdded} />
            <AddCategorisationRuleForm
                onAdded={() => {
                    setAwaitingFocus(true);
                }}
                focusRequested={fallbackFocusRequested}
                onFocusRequestHandled={clearFallbackFocus}
            />
        </>
    );
}
