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
 */
function RulesList({
    awaitingFocus,
    onFocused,
}: {
    readonly awaitingFocus: boolean;
    /** Called once focus has been applied, so the request to focus can be cleared. */
    readonly onFocused: () => void;
}) {
    const rules = useCategorisationRules();
    const lastItemRef = useRef<HTMLLIElement>(null);

    useEffect(() => {
        if (awaitingFocus && rules.data !== undefined && rules.data.length > 0) {
            lastItemRef.current?.focus();
            onFocused();
        }
    }, [awaitingFocus, rules.data, onFocused]);

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
 * success signal, mirroring `AccountsPage` and `BudgetsPage`'s own pattern.
 */
export function CategorisationRulesPage() {
    const [awaitingFocus, setAwaitingFocus] = useState(false);
    // Stable, so the list's focus effect does not run again on every render of the page.
    const clearAwaitingFocus = useCallback(() => {
        setAwaitingFocus(false);
    }, []);

    return (
        <>
            <h1>Categorisation rules</h1>
            <p className={styles.hint}>{HINT}</p>
            <RulesList awaitingFocus={awaitingFocus} onFocused={clearAwaitingFocus} />
            <AddCategorisationRuleForm
                onAdded={() => {
                    setAwaitingFocus(true);
                }}
            />
        </>
    );
}
