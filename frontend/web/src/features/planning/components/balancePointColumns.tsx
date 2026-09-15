import { type Column, DateText, MoneyText } from '@/shared/ui';

import type { BalancePoint } from '../types/projection';

/**
 * The columns of a table of step boundaries: Date, heading each row, then Cash, Invested and Total.
 *
 * Every amount is rendered exactly as the ledger sent it, sign included, through `MoneyText` -
 * never recomputed or re-signed (ADR-029).
 */
export const BALANCE_POINT_COLUMNS: readonly Column<BalancePoint>[] = [
    {
        key: 'date',
        header: 'Date',
        align: 'end',
        rowHeader: true,
        render: (point) => <DateText value={point.date} />,
    },
    {
        key: 'cash',
        header: 'Cash',
        align: 'end',
        render: (point) => <MoneyText amount={point.cash.amount} currency={point.cash.currency} />,
    },
    {
        key: 'invested',
        header: 'Invested',
        align: 'end',
        render: (point) => (
            <MoneyText amount={point.invested.amount} currency={point.invested.currency} />
        ),
    },
    {
        key: 'total',
        header: 'Total',
        align: 'end',
        render: (point) => (
            <MoneyText amount={point.total.amount} currency={point.total.currency} />
        ),
    },
];
