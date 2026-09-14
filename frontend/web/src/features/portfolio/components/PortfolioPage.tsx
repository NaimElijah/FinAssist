import { EmptyState } from '@/shared/ui';

/** The portfolio area's landing page: it names the area and says the area is not built yet. */
export function PortfolioPage() {
    return (
        <>
            <h1>Portfolio</h1>
            <EmptyState message="Portfolio is not built yet." />
        </>
    );
}
