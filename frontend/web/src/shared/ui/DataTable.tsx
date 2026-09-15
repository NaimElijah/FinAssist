import { type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { joinClassNames } from './classNames';
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
    /**
     * Render this column's body cells as `<th scope="row">`, so a screen reader names the row as
     * it moves across it. Set it on the column that identifies a row, such as the account name.
     * Defaults to `false`.
     */
    readonly rowHeader?: boolean;
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
 * A semantic table, typed over its row type, inside its own horizontal scroll region.
 *
 * Every cell uses tabular numerals, so figures in an `end`-aligned column line up digit for digit.
 * Alignment is exposed on each header and body cell as `data-align`, which the stylesheet keys
 * on. An empty `rows` renders the header row alone; show an `EmptyState` instead when there is
 * nothing to tabulate.
 *
 * A table wider than the screen scrolls inside a `role="region"` wrapper rather than making the
 * whole page scroll sideways. The wrapper is named by the caption, so what it holds is announced.
 * It is a Tab stop only while the table overflows it, so a keyboard user can scroll it with the
 * arrow keys: a region with nothing to scroll would only be one more key press before the table's
 * own links and buttons.
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
    const captionId = useId();
    const regionRef = useRef<HTMLDivElement>(null);
    const [overflowing, setOverflowing] = useState(false);

    // Measured after layout, and again whenever the window's size changes: whether the table
    // overflows depends on both what it holds and how much room the page gives it.
    useEffect(() => {
        const region = regionRef.current;
        if (region === null) {
            return;
        }
        const measure = () => {
            setOverflowing(region.scrollWidth > region.clientWidth);
        };
        measure();
        window.addEventListener('resize', measure);
        return () => {
            window.removeEventListener('resize', measure);
        };
    }, [rows, columns]);

    return (
        <div
            ref={regionRef}
            role="region"
            aria-labelledby={captionId}
            tabIndex={overflowing ? 0 : undefined}
            className={styles.region}
        >
            <table className={styles.table}>
                <caption
                    id={captionId}
                    className={captionHidden ? styles.hiddenCaption : styles.caption}
                >
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
                            {columns.map((column) =>
                                column.rowHeader === true ? (
                                    <th
                                        key={column.key}
                                        scope="row"
                                        data-align={column.align ?? 'start'}
                                        className={joinClassNames(styles.cell, styles.rowHeader)}
                                    >
                                        {column.render(row)}
                                    </th>
                                ) : (
                                    <td
                                        key={column.key}
                                        data-align={column.align ?? 'start'}
                                        className={styles.cell}
                                    >
                                        {column.render(row)}
                                    </td>
                                ),
                            )}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
