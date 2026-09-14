import type { ReactNode } from 'react';

import styles from './DataTable.module.css';

/** The edge a column's content sits against. Money and date columns use `end`. */
export type ColumnAlign = 'start' | 'end';

/** One column of a `DataTable` over rows of type `Row`. */
export interface Column<Row> {
    /** Unique among the table's columns; the React key for its header and cells. */
    readonly key: string;
    readonly header: string;
    /** Defaults to `start`. Use `end` for money and dates, so their digits line up. */
    readonly align?: ColumnAlign;
    /** The content of this column's cell for one row. */
    readonly render: (row: Row) => ReactNode;
}

/** The rows to show, how to show each column, and what the table is called. */
export interface DataTableProps<Row> {
    /**
     * The table's accessible name. Required: an unnamed table is a grid of figures with no
     * subject for anyone navigating by table.
     */
    readonly caption: string;
    /**
     * Hide the caption visually while keeping it as the accessible name, for a table sitting
     * under a heading that already names it. Defaults to `false`.
     */
    readonly captionHidden?: boolean;
    readonly columns: readonly Column<Row>[];
    readonly rows: readonly Row[];
    /** A stable, unique key per row: an identifier from the wire, never an array index. */
    readonly rowKey: (row: Row) => string;
}

/**
 * A semantic table, typed over its row type.
 *
 * Every cell uses tabular numerals, so figures in an `end`-aligned column line up digit for digit.
 * Alignment is exposed on each header and body cell as `data-align`, which the stylesheet keys
 * on. An empty `rows` renders the header row alone; show an `EmptyState` instead when there is
 * nothing to tabulate.
 *
 * @param props - see `DataTableProps`.
 */
export function DataTable<Row>({
    caption,
    captionHidden = false,
    columns,
    rows,
    rowKey,
}: DataTableProps<Row>) {
    return (
        <table className={styles.table}>
            <caption className={captionHidden ? styles.hiddenCaption : styles.caption}>
                {caption}
            </caption>
            <thead>
                <tr>
                    {columns.map((column) => (
                        <th
                            key={column.key}
                            scope="col"
                            data-align={column.align ?? 'start'}
                            className={styles.header}
                        >
                            {column.header}
                        </th>
                    ))}
                </tr>
            </thead>
            <tbody>
                {rows.map((row) => (
                    <tr key={rowKey(row)} className={styles.row}>
                        {columns.map((column) => (
                            <td
                                key={column.key}
                                data-align={column.align ?? 'start'}
                                className={styles.cell}
                            >
                                {column.render(row)}
                            </td>
                        ))}
                    </tr>
                ))}
            </tbody>
        </table>
    );
}
