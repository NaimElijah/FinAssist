/**
 * The portfolio feature's server state, held in TanStack Query's cache (ADR-029).
 *
 * Every query key comes from `portfolioKeys`, so a refresh invalidates exactly what a query reads
 * rather than a key spelled twice.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { priceDisposal, readPosition, valueHolding } from '../api/portfolioApi';
import type { DisposalInput } from '../types/disposal';

/** The prefix every holding's key starts with. */
const HOLDINGS_KEY = ['portfolio', 'holdings'] as const;

/**
 * The query keys the portfolio feature reads.
 *
 * `holding(symbol)` is the parent of that symbol's `position` and `valuation`, so "every read about
 * one holding" is a plain prefix. A prefix matches element by element, so `VOO`'s never matches
 * `VOOG`'s reads.
 */
export const portfolioKeys = {
    /** The prefix of every read about one holding. */
    holding: (symbol: string) => [...HOLDINGS_KEY, symbol] as const,
    /** One holding's position. */
    position: (symbol: string) => [...HOLDINGS_KEY, symbol, 'position'] as const,
    /** One holding's valuation. */
    valuation: (symbol: string) => [...HOLDINGS_KEY, symbol, 'valuation'] as const,
};

/**
 * What is held under one symbol, and what it cost.
 *
 * @param symbol - the symbol as it appears in the URL; each spelling is its own cache entry.
 * @returns the query for the position. Its error is an `ApiError` - 404 `HOLDING_NOT_FOUND`, never
 *   retried - or an `ApiBodyError` from the client, or a `TypeError` for a network failure.
 */
export function usePosition(symbol: string) {
    return useQuery({
        queryKey: portfolioKeys.position(symbol),
        queryFn: () => readPosition(symbol),
    });
}

/**
 * One holding valued at the latest usable price.
 *
 * Read beside the position rather than after it, so neither waits on the other.
 *
 * @param symbol - the symbol as it appears in the URL; each spelling is its own cache entry.
 * @returns the query for the valuation. Its error is an `ApiError` - 404 `HOLDING_NOT_FOUND` or 503
 *   `PRICE_UNAVAILABLE`, neither retried (see `src/app/queryClient.ts`) - or an `ApiBodyError`
 *   from the client, or a `TypeError` for a network failure.
 */
export function useValuation(symbol: string) {
    return useQuery({
        queryKey: portfolioKeys.valuation(symbol),
        queryFn: () => valueHolding(symbol),
    });
}

/**
 * A way to read one holding afresh, for the lookup to call as it submits.
 *
 * Looking up the symbol already on screen navigates to the URL already open, which changes no
 * query key, and refetching on window focus is off (`src/app/queryClient.ts`), so without this the
 * owner could not see a newer figure short of reloading the page. Invalidating the holding's
 * prefix refetches its position and valuation at once when they are on screen, and otherwise marks
 * them stale, so the page mounting them next reads them again.
 *
 * @returns a function marking every read about `symbol` stale.
 */
export function useRefreshHolding(): (symbol: string) => void {
    const queryClient = useQueryClient();
    return (symbol) => {
        // The promise settles once the refetch does and never rejects: a failure shows through
        // each query's own error, so there is nothing here to await.
        void queryClient.invalidateQueries({ queryKey: portfolioKeys.holding(symbol) });
    };
}

/**
 * Price a disposal of one holding: what selling units would realise, and the lots that would
 * remain.
 *
 * A mutation rather than a query, because it runs when the owner submits and nothing should cache
 * or refetch it. It records nothing, so it invalidates no key: no position, valuation or lot can
 * have changed.
 *
 * @param symbol - the holding to price a disposal of, as it appears in the URL.
 * @returns the mutation. It is never retried (see `src/app/queryClient.ts`).
 */
export function usePriceDisposal(symbol: string) {
    return useMutation({
        mutationFn: (input: DisposalInput) => priceDisposal(symbol, input),
    });
}
