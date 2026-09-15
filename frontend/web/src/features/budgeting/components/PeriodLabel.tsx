import { DateText } from '@/shared/ui';

import { periodDisplayEnd, periodMonthLabel } from '../types/period';
import type { BudgetPeriod } from '../types/period';

/**
 * A budget's period, as a month name and year when it covers exactly one calendar month, or
 * otherwise as its start and display end (the day before the exclusive `end`), each through
 * `DateText`.
 *
 * Shared by `BudgetsPage`'s list and `BudgetReviewPage`'s header, so the two screens never
 * disagree on how a period reads.
 */
export function PeriodLabel({ period }: { readonly period: BudgetPeriod }) {
    const monthLabel = periodMonthLabel(period);
    if (monthLabel !== undefined) {
        return <>{monthLabel}</>;
    }
    return (
        <>
            <DateText value={period.start} /> – <DateText value={periodDisplayEnd(period)} />
        </>
    );
}
