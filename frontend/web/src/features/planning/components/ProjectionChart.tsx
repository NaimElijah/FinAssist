import { useId } from 'react';

import { DateText, MoneyText } from '@/shared/ui';

import type { Projection } from '../types/projection';
import { CADENCE_LABELS } from '../types/recurrence';
import { type ChartExtreme, type ChartLine, scalePoints } from './chartScale';
import styles from './ProjectionChart.module.css';

/** The plot's size in SVG user units; the `viewBox` scales it to whatever width the page gives. */
const PLOT_WIDTH = 600;
const PLOT_HEIGHT = 240;

/** Room around the plot, so a stroke or an extreme's mark on an edge is not clipped. */
const PLOT_MARGIN = 8;

/** The invested line's dashes: the two lines differ in stroke style, never in colour alone. */
const INVESTED_DASH = '6 4';

const EXTREME_MARK_RADIUS = 4;

/** Coordinates are written to one decimal place: finer than a pixel, and a stable string. */
const COORDINATE_DECIMALS = 1;

/** The words naming each line beside an extreme's value. */
const LINE_WORDS: Readonly<Record<ChartLine, string>> = { cash: 'cash', invested: 'invested' };

function coordinate(value: number): string {
    return value.toFixed(COORDINATE_DECIMALS);
}

/** An SVG path moving to the first vertex and drawing a straight line to each one after it. */
function pathThrough(vertices: readonly { readonly x: number; readonly y: number }[]): string {
    return vertices
        .map(({ x, y }, index) => `${index === 0 ? 'M' : 'L'}${coordinate(x)} ${coordinate(y)}`)
        .join(' ');
}

/** An extreme's value exactly as the ledger sent it, and which line reaches it. */
function ExtremeValue({ extreme }: { readonly extreme: ChartExtreme }) {
    return (
        <>
            <MoneyText amount={extreme.amount.amount} currency={extreme.amount.currency} /> (
            {LINE_WORDS[extreme.line]})
        </>
    );
}

/** A legend entry: a short sample of the line's own stroke, and its name. */
function LegendItem({
    className,
    dash,
    label,
}: {
    readonly className: string | undefined;
    readonly dash?: string;
    readonly label: string;
}) {
    return (
        <li className={styles.legendItem}>
            <svg className={styles.swatch} viewBox="0 0 24 12" aria-hidden="true">
                <line x1="0" y1="6" x2="24" y2="6" className={className} strokeDasharray={dash} />
            </svg>
            {label}
        </li>
    );
}

/** What `ProjectionChart` draws. */
export interface ProjectionChartProps {
    readonly projection: Projection;
}

/**
 * A scenario's projected cash and invested lines, drawn as a hand-rolled SVG sized by its
 * `viewBox`, so it follows the width it is given.
 *
 * Cash is solid and invested dashed, in different colours too. A zero line is drawn when a value
 * falls below zero, and a vertical marker stands at `firstShortfall` when there is one. Every
 * figure and date shown - the horizon's start and end, the highest and lowest values - is the
 * ledger's own string, through `DateText` and `MoneyText`; only positions are computed, in
 * `scalePoints` (ADR-029). The SVG's title and description therefore carry no figure at all, and
 * name the Projection table beneath the chart as where every figure is read.
 *
 * @param props - see `ProjectionChartProps`.
 */
export function ProjectionChart({ projection }: ProjectionChartProps) {
    const titleId = useId();
    const descriptionId = useId();
    const scaled = scalePoints(projection.points, PLOT_WIDTH, PLOT_HEIGHT);
    const shortfallIndex = projection.points.findIndex(
        (point) => point.date === projection.firstShortfall,
    );
    const shortfallX = scaled.points[shortfallIndex]?.x;
    const stepWord = CADENCE_LABELS[projection.step].toLowerCase();

    return (
        <figure className={styles.figure}>
            <svg
                role="img"
                aria-labelledby={`${titleId} ${descriptionId}`}
                viewBox={`${String(-PLOT_MARGIN)} ${String(-PLOT_MARGIN)} ${String(PLOT_WIDTH + 2 * PLOT_MARGIN)} ${String(PLOT_HEIGHT + 2 * PLOT_MARGIN)}`}
                className={styles.svg}
            >
                <title id={titleId}>Projected cash and invested balances</title>
                <desc id={descriptionId}>
                    {`Cash as a solid line and invested as a dashed line, at each ${stepWord} step of the horizon. The Projection table lists every figure.`}
                </desc>
                {scaled.zeroY === undefined ? null : (
                    <line
                        data-marker="zero"
                        x1={coordinate(0)}
                        y1={coordinate(scaled.zeroY)}
                        x2={coordinate(PLOT_WIDTH)}
                        y2={coordinate(scaled.zeroY)}
                        className={styles.zero}
                    />
                )}
                {shortfallX === undefined ? null : (
                    <line
                        data-marker="first-shortfall"
                        x1={coordinate(shortfallX)}
                        y1={coordinate(0)}
                        x2={coordinate(shortfallX)}
                        y2={coordinate(PLOT_HEIGHT)}
                        className={styles.shortfall}
                    />
                )}
                <path
                    data-line="cash"
                    d={pathThrough(scaled.points.map(({ x, cashY }) => ({ x, y: cashY })))}
                    className={styles.cash}
                />
                <path
                    data-line="invested"
                    d={pathThrough(scaled.points.map(({ x, investedY }) => ({ x, y: investedY })))}
                    strokeDasharray={INVESTED_DASH}
                    className={styles.invested}
                />
                {[scaled.highest, scaled.lowest].map((extreme, index) => (
                    <circle
                        // The two extremes are a fixed pair, highest then lowest.
                        key={index}
                        cx={coordinate(extreme.x)}
                        cy={coordinate(extreme.y)}
                        r={EXTREME_MARK_RADIUS}
                        className={styles.extremeMark}
                    />
                ))}
            </svg>
            <div className={styles.axis}>
                <DateText value={projection.horizon.from} />
                <DateText value={projection.horizon.to} />
            </div>
            <dl className={styles.extremes}>
                <div className={styles.extreme}>
                    <dt>Highest</dt>
                    <dd>
                        <ExtremeValue extreme={scaled.highest} />
                    </dd>
                </div>
                <div className={styles.extreme}>
                    <dt>Lowest</dt>
                    <dd>
                        <ExtremeValue extreme={scaled.lowest} />
                    </dd>
                </div>
            </dl>
            <ul aria-label="Legend" className={styles.legend}>
                <LegendItem className={styles.cash} label="Cash, solid line" />
                <LegendItem
                    className={styles.invested}
                    dash={INVESTED_DASH}
                    label="Invested, dashed line"
                />
                {scaled.zeroY === undefined ? null : (
                    <LegendItem className={styles.zero} label="Zero" />
                )}
                {shortfallX === undefined ? null : (
                    <LegendItem className={styles.shortfall} label="First shortfall" />
                )}
            </ul>
        </figure>
    );
}
