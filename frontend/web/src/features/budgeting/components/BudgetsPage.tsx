import { EmptyState } from '@/shared/ui';

/** The budgeting area's landing page: it names the area and says the area is not built yet. */
export function BudgetsPage() {
    return (
        <>
            <h1>Budgets</h1>
            <EmptyState message="Budgets are not built yet." />
        </>
    );
}
