import { AdviceNote } from './AdviceNote';
import { SymbolLookup } from './SymbolLookup';

/**
 * `/portfolio`: the portfolio area's landing page, where a holding is looked up by its symbol.
 *
 * It lists no holdings: the assistant reads a holding one symbol at a time.
 */
export function PortfolioPage() {
    return (
        <>
            <h1>Portfolio</h1>
            <AdviceNote />
            <SymbolLookup heading="Look up a holding" />
        </>
    );
}
