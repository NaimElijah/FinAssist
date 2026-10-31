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
 * A table wider than the screen scrolls inside its own wrapper rather than making the whole page
 * scroll sideways. Only while the table overflows it is the wrapper a Tab stop, so a keyboard user
 * can scroll it with the arrow keys, and a `region` named by the caption, so what that stop holds
 * is announced. A wrapper with nothing to scroll is neither: it would only be one more key press,
 * and one more landmark named like the table, before the table itself.
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
    const tableRef = useRef<HTMLTableElement>(null);
    const [overflowing, setOverflowing] = useState(false);

    // Measured after layout, then whenever the region or the table changes size. The region
    // changes when the page gives it more or less room: a window resize or a page zoom, which
    // fire a window resize event, and a sibling appearing, which does not. The table changes when
    // what it holds reflows: new rows, a web font loading, or a change to the browser's font size
    // widening the columns inside an unchanged region. None of those fires a window resize
    // event, so a window listener missed them (#72).
    useEffect(() => {
        const region = regionRef.current;
        const table = tableRef.current;
        if (region === null || table === null) {
            return;
        }
        const measure = () => {
            setOverflowing(region.scrollWidth > region.clientWidth);
        };
        measure();
        const observer = new ResizeObserver(measure);
        observer.observe(region);
        observer.observe(table);
        return () => {
            observer.disconnect();
        };
    }, []);

    return (
        <div
            ref={regionRef}
            role={overflowing ? 'region' : undefined}
            aria-labelledby={overflowing ? captionId : undefined}
            tabIndex={overflowing ? 0 : undefined}
            className={styles.region}
        >
            <table ref={tableRef} className={styles.table}>
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
