import type { UseQueryResult } from '@tanstack/react-query';
import { useId } from 'react';
import { Link, useParams } from 'react-router';

import { ApiError } from '@/shared/api/client';
import { useDocumentTitle } from '@/shared/lib/documentTitle';
import { EmptyState, MoneyText } from '@/shared/ui';

import { HOLDING_NOT_FOUND, PRICE_UNAVAILABLE } from '../api/portfolioApi';
import { usePosition, useValuation } from '../hooks/portfolioQueries';
import type { Position, Valuation } from '../types/holding';
import { gainOrLoss } from '../types/money';
import { AdviceNote } from './AdviceNote';
import { Figure, Figures } from './Figures';
import styles from './HoldingPage.module.css';
import { PORTFOLIO_PATH } from './paths';
import { ReadFailure } from './ReadFailure';
import { SymbolLookup } from './SymbolLookup';

const NO_PRICE_MESSAGE =
    'No usable price right now — a valuation needs a quote no more than an hour old';

/** Whether a read failed with the problem `code`, rather than for any other reason. */
function failedWith(error: Error | null, code: string): boolean {
    return error instanceof ApiError && error.problem?.code === code;
}

function PositionSection({ query }: { readonly query: UseQueryResult<Position> }) {
    const headingId = useId();
    const position = query.data;
    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Position
            </h2>
            {query.isPending ? <p className={styles.pending}>Loading position…</p> : null}
            {/* A failed refetch sits above the figures it failed to refresh, which stay. */}
            {query.isError ? <ReadFailure query={query} /> : null}
            {position === undefined ? null : (
                <Figures>
                    {/* A number of units, not money: shown exactly as received, never formatted. */}
                    <Figure term="Quantity">{position.quantity}</Figure>
                    <Figure term="Cost">
                        <MoneyText
                            amount={position.cost.amount}
                            currency={position.cost.currency}
                        />
                    </Figure>
                </Figures>
            )}
        </section>
    );
}

function ValuationSection({ query }: { readonly query: UseQueryResult<Valuation> }) {
    const headingId = useId();
    const noPrice = failedWith(query.error, PRICE_UNAVAILABLE);
    // A valuation kept from before the price went stale is exactly what the 503 refuses to give,
    // so it is not shown beside the no-price state.
    const valuation = noPrice ? undefined : query.data;
    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Valuation
            </h2>
            {query.isPending ? <p className={styles.pending}>Loading valuation…</p> : null}
            {noPrice ? <EmptyState message={NO_PRICE_MESSAGE} /> : null}
            {query.isError && !noPrice ? <ReadFailure query={query} /> : null}
            {valuation === undefined ? null : (
                <Figures>
                    <Figure term="Unit price">
                        <MoneyText
                            amount={valuation.unitPrice.amount}
                            currency={valuation.unitPrice.currency}
                        />
                    </Figure>
                    <Figure term="Market value">
                        <MoneyText
                            amount={valuation.marketValue.amount}
                            currency={valuation.marketValue.currency}
                        />
                    </Figure>
                    <Figure term={`Unrealised ${gainOrLoss(valuation.unrealisedGain.amount)}`}>
                        <MoneyText
                            amount={valuation.unrealisedGain.amount}
                            currency={valuation.unrealisedGain.currency}
                        />
                    </Figure>
                </Figures>
            )}
        </section>
    );
}

/**
 * One holding's page, for a symbol taken from the URL.
 *
 * The position and the valuation are read side by side, and each section shows its own state.
 * Nothing held (404 `HOLDING_NOT_FOUND` on the position) is a calm empty state in place of both
 * sections, rather than a second error from the valuation. No usable price (503
 * `PRICE_UNAVAILABLE`) is an empty state in the valuation's section alone, and the position still
 * shows. Any other failure is an error state offering a retry.
 */
function Holding({ symbol }: { readonly symbol: string }) {
    const position = usePosition(symbol);
    const valuation = useValuation(symbol);
    useDocumentTitle(`${symbol} holding`);
    const nothingHeld = failedWith(position.error, HOLDING_NOT_FOUND);

    return (
        <>
            <p className={styles.back}>
                <Link to={PORTFOLIO_PATH}>Back to Portfolio</Link>
            </p>
            <h1>{symbol}</h1>
            <AdviceNote />
            {nothingHeld ? (
                <EmptyState message={`Nothing is held under ${symbol}`} />
            ) : (
                <>
                    <PositionSection query={position} />
                    <ValuationSection query={valuation} />
                </>
            )}
            <SymbolLookup heading="Look up another holding" />
        </>
    );
}

/**
 * `/portfolio/:symbol`: one holding's position and valuation, and a lookup for another.
 *
 * @throws Error - when mounted on a route without a `:symbol` segment, which only a broken route
 *   table can do.
 */
export function HoldingPage() {
    const { symbol } = useParams();
    if (symbol === undefined) {
        throw new Error('HoldingPage is mounted on a route without a :symbol segment');
    }
    return <Holding symbol={symbol} />;
}
