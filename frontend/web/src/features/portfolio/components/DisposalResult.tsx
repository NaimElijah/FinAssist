import { useEffect, useId, useRef } from 'react';

import { type Column, DataTable, DateText, EmptyState, MoneyText } from '@/shared/ui';

import type { Disposition, Lot } from '../types/disposal';
import { gainOrLoss } from '../types/money';
import styles from './DisposalResult.module.css';
import { Figure, Figures } from './Figures';

/** One remaining lot, and its place among those remaining. */
interface LotRow {
    readonly lot: Lot;
    /**
     * Oldest first, from 0. A lot carries no id on the wire, and two bought on one day at one price
     * are indistinguishable, so its place is its identity within a result; each new result replaces
     * the table whole and never reorders it.
     */
    readonly place: number;
}

const LOT_COLUMNS: readonly Column<LotRow>[] = [
    {
        key: 'acquired',
        header: 'Acquired',
        align: 'end',
        rowHeader: true,
        render: (row) => <DateText value={row.lot.acquiredOn} />,
    },
    {
        key: 'quantity',
        header: 'Quantity',
        align: 'end',
        // A number of units, not money: shown exactly as received, never formatted.
        render: (row) => row.lot.quantity,
    },
    {
        key: 'unitCost',
        header: 'Unit cost',
        align: 'end',
        render: (row) => (
            <MoneyText amount={row.lot.unitCost.amount} currency={row.lot.unitCost.currency} />
        ),
    },
];

/** What a disposal calculation found. */
export interface DisposalResultProps {
    readonly disposition: Disposition;
}

/**
 * What a calculation found: the cost of sale, the realised gain or loss, and the lots that would
 * remain, oldest first.
 *
 * Its heading takes focus when it appears. Submit was disabled while the calculation ran, which
 * leaves focus nowhere, and the owner should hear the answer. A new calculation unmounts the
 * previous result while it runs, so every answer takes focus once.
 *
 * @param props - see `DisposalResultProps`.
 */
export function DisposalResult({ disposition }: DisposalResultProps) {
    const headingId = useId();
    const headingRef = useRef<HTMLHeadingElement>(null);
    const rows: readonly LotRow[] = disposition.remaining.map((lot, place) => ({ lot, place }));

    useEffect(() => {
        headingRef.current?.focus();
    }, []);

    return (
        <section aria-labelledby={headingId} className={styles.result}>
            <h3 id={headingId} ref={headingRef} tabIndex={-1} className={styles.heading}>
                What this sale would realise
            </h3>
            <Figures>
                <Figure term="Cost of sale">
                    <MoneyText
                        amount={disposition.costOfSale.amount}
                        currency={disposition.costOfSale.currency}
                    />
                </Figure>
                <Figure term={`Realised ${gainOrLoss(disposition.realisedGain.amount)}`}>
                    <MoneyText
                        amount={disposition.realisedGain.amount}
                        currency={disposition.realisedGain.currency}
                    />
                </Figure>
            </Figures>
            {rows.length === 0 ? (
                <EmptyState message="No lots remaining after this disposal" />
            ) : (
                <DataTable
                    caption="Lots remaining after this disposal"
                    columns={LOT_COLUMNS}
                    rows={rows}
                    rowKey={(row) => String(row.place)}
                />
            )}
        </section>
    );
}
