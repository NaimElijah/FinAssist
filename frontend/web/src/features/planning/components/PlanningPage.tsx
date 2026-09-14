import { EmptyState } from '@/shared/ui';

/** The planning area's landing page: it names the area and says the area is not built yet. */
export function PlanningPage() {
    return (
        <>
            <h1>Planning</h1>
            <EmptyState message="Planning is not built yet." />
        </>
    );
}
