import { EmptyState } from '@/shared/ui';

/** The accounting area's landing page: it names the area and says the area is not built yet. */
export function AccountsPage() {
    return (
        <>
            <h1>Accounts</h1>
            <EmptyState message="Accounts are not built yet." />
        </>
    );
}
