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
import { DisposalSection } from './DisposalSection';
import { Figure, Figures } from './Figures';
import styles from './HoldingPage.module.css';
import { PORTFOLIO_PATH } from './paths';
import { ReadFailure } from './ReadFailure';
import { SymbolLookup } from './SymbolLookup';

const NO_PRICE_MESSAGE =
    'No usable price right now — a valuation needs a quote no more than an hour old';

/** The calm answer for a symbol under which nothing is held. */
function nothingHeldMessage(symbol: string): string {
    return `Nothing is held under ${symbol}`;
}

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

function ValuationSection({
    symbol,
    query,
}: {
    readonly symbol: string;
    readonly query: UseQueryResult<Valuation>;
}) {
    const headingId = useId();
    const noPrice = failedWith(query.error, PRICE_UNAVAILABLE);
    // Both reads answer 404 when nothing is held, and either can land first. The position's 404
    // replaces the page once it lands; until then the valuation's is the same considered answer,
    // not a failure to offer a retry for.
    const nothingHeld = failedWith(query.error, HOLDING_NOT_FOUND);
    const considered = noPrice || nothingHeld;
    // A valuation kept from an earlier read is exactly what either answer says cannot be given now,
    // so it is not shown beside them.
    const valuation = considered ? undefined : query.data;
    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 id={headingId} className={styles.heading}>
                Valuation
            </h2>
            {query.isPending ? <p className={styles.pending}>Loading valuation…</p> : null}
            {noPrice ? <EmptyState message={NO_PRICE_MESSAGE} /> : null}
            {nothingHeld ? <EmptyState message={nothingHeldMessage(symbol)} /> : null}
            {query.isError && !considered ? <ReadFailure query={query} /> : null}
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
 * Nothing held (404 `HOLDING_NOT_FOUND` on the position) is a calm empty state in place of the
 * position, the valuation and the disposal calculator, rather than a second error from the
 * valuation and a calculator with nothing to price; should the valuation's own 404 land first, its
 * section shows the same calm state until then. No usable price (503 `PRICE_UNAVAILABLE`) is an
 * empty state in the valuation's section alone, and the position still shows. Any other failure is
 * an error state offering a retry.
 *
 * The disposal calculator is offered once the position has loaded, its proceeds starting in the
 * currency the holding's lots cost, the only one the assistant prices them in. It is keyed by
 * symbol, so moving to another holding starts it afresh rather than showing one holding's
 * calculation under another's name. The lookup is keyed by symbol too, so text typed on one
 * holding's page does not follow the owner onto the next.
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
                <EmptyState message={nothingHeldMessage(symbol)} />
            ) : (
                <>
                    <PositionSection query={position} />
                    <ValuationSection symbol={symbol} query={valuation} />
                    {position.data === undefined ? null : (
                        <DisposalSection
                            key={symbol}
                            symbol={symbol}
                            initialCurrency={position.data.cost.currency}
                        />
                    )}
                </>
            )}
            <SymbolLookup key={symbol} heading="Look up another holding" />
        </>
    );
}

/**
 * `/portfolio/:symbol`: one holding's position and valuation, a calculator pricing a disposal of
 * it, and a lookup for another.
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
