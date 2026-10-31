import type { UseQueryResult } from '@tanstack/react-query';
import { useId } from 'react';
import { Link, useParams } from 'react-router';

import { hasProblemCode } from '@/shared/api/client';
import { useDocumentTitle } from '@/shared/lib/documentTitle';
import { EmptyState, LoadingNote, MoneyText, QueryErrorState, useRetryFocus } from '@/shared/ui';

import { HOLDING_NOT_FOUND, PRICE_UNAVAILABLE } from '../api/portfolioApi';
import { usePosition, useValuation } from '../hooks/portfolioQueries';
import type { Position, Valuation } from '../types/holding';
import { gainOrLoss } from '../types/money';
import { AdviceNote } from './AdviceNote';
import { DisposalSection } from './DisposalSection';
import { Figure, Figures } from './Figures';
import styles from './HoldingPage.module.css';
import { PORTFOLIO_PATH } from './paths';
import { SymbolLookup } from './SymbolLookup';

/**
 * Names no staleness window: how old a usable quote may be is the assistant's to decide, and a
 * figure written here would go false the moment it changed there.
 */
const NO_PRICE_MESSAGE = 'No usable price right now';

/** The calm answer for a symbol under which nothing is held. */
function nothingHeldMessage(symbol: string): string {
    return `Nothing is held under ${symbol}`;
}

function PositionSection({ query }: { readonly query: UseQueryResult<Position> }) {
    const headingId = useId();
    const { targetRef, onRetry } = useRetryFocus<HTMLHeadingElement>(
        query.error,
        query.isFetching,
        () => {
            void query.refetch();
        },
    );
    const position = query.data;
    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 ref={targetRef} id={headingId} tabIndex={-1} className={styles.heading}>
                Position
            </h2>
            {query.isPending ? <LoadingNote message="Loading position…" /> : null}
            {/* A failed refetch sits above the figures it failed to refresh, which stay. */}
            <QueryErrorState error={query.error} retrying={query.isFetching} onRetry={onRetry} />
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
    const { targetRef, onRetry } = useRetryFocus<HTMLHeadingElement>(
        query.error,
        query.isFetching,
        () => {
            void query.refetch();
        },
    );
    const noPrice = hasProblemCode(query.error, PRICE_UNAVAILABLE);
    // Both reads answer 404 when nothing is held, and either can land first. The position's 404
    // replaces the page once it lands; until then the valuation's is the same considered answer,
    // not a failure to offer a retry for.
    const nothingHeld = hasProblemCode(query.error, HOLDING_NOT_FOUND);
    const considered = noPrice || nothingHeld;
    // A valuation kept from an earlier read is exactly what either answer says cannot be given now,
    // so it is not shown beside them.
    const valuation = considered ? undefined : query.data;
    return (
        <section aria-labelledby={headingId} className={styles.section}>
            <h2 ref={targetRef} id={headingId} tabIndex={-1} className={styles.heading}>
                Valuation
            </h2>
            {query.isPending ? <LoadingNote message="Loading valuation…" /> : null}
            {noPrice ? <EmptyState message={NO_PRICE_MESSAGE} /> : null}
            {nothingHeld ? <EmptyState message={nothingHeldMessage(symbol)} /> : null}
            {considered ? null : (
                <QueryErrorState
                    error={query.error}
                    retrying={query.isFetching}
                    onRetry={onRetry}
                />
            )}
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
 * an error state, offering a retry where a second attempt could change the answer.
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
    const nothingHeld = hasProblemCode(position.error, HOLDING_NOT_FOUND);

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
 * The page for an address whose symbol is blank, such as `/portfolio/%20`. The lookup refuses a
 * blank symbol, but an address can still carry one, and no holding is kept under it: asking the
 * assistant would only earn a 422 for each read, shown as two failures under an empty heading.
 */
function BlankSymbol() {
    return (
        <>
            <p className={styles.back}>
                <Link to={PORTFOLIO_PATH}>Back to Portfolio</Link>
            </p>
            <h1>Holding not found</h1>
            <EmptyState message="This address names no symbol to look up." />
            <SymbolLookup heading="Look up a holding" />
        </>
    );
}

/**
 * `/portfolio/:symbol`: one holding's position and valuation, a calculator pricing a disposal of
 * it, and a lookup for another. A blank symbol is not found, and nothing is read for it.
 *
 * @throws Error - when mounted on a route without a `:symbol` segment, which only a broken route
 *   table can do.
 */
export function HoldingPage() {
    const { symbol } = useParams();
    if (symbol === undefined) {
        throw new Error('HoldingPage is mounted on a route without a :symbol segment');
    }
    if (symbol.trim() === '') {
        return <BlankSymbol />;
    }
    return <Holding symbol={symbol} />;
}
