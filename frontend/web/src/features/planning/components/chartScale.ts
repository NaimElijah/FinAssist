/**
 * Where a projection's points sit on its chart: pixel coordinates, and nothing a person reads.
 */
import type { Money } from '../types/money';
import type { BalancePoint } from '../types/projection';

/** The two lines a projection chart draws. `total` is in the table beneath it, not on the chart. */
export type ChartLine = 'cash' | 'invested';

const LINES: readonly ChartLine[] = ['cash', 'invested'];

/** One step boundary's position: across the plot, and each line's height at it. */
export interface ScaledPoint {
    readonly x: number;
    readonly cashY: number;
    readonly investedY: number;
}

/** The highest or lowest value either line reaches, and where it is drawn. */
export interface ChartExtreme {
    /**
     * The very amount the ledger sent for the point holding this extreme. Its label renders this
     * string, never the number the position was computed from.
     */
    readonly amount: Money;
    readonly line: ChartLine;
    readonly x: number;
    readonly y: number;
}

/** A projection's points placed on a plot, with its zero line and its two extremes. */
export interface ScaledChart {
    /** One per input point, in the same order. */
    readonly points: readonly ScaledPoint[];
    /** Where zero is drawn, when a value falls below it; `undefined` when none does. */
    readonly zeroY: number | undefined;
    readonly highest: ChartExtreme;
    readonly lowest: ChartExtreme;
}

/** An extreme found so far: which amount holds it, and its value as a coordinate input. */
interface Candidate {
    readonly amount: Money;
    readonly line: ChartLine;
    readonly index: number;
    readonly value: number;
}

/**
 * Place a projection's cash and invested lines on a plot `width` wide and `height` tall, with
 * `(0, 0)` at the top left, so a higher value has a smaller y.
 *
 * ADR-029: the browser does no arithmetic on money. An amount may be converted to a number only
 * inside this function, the chart's scale function, to compute a pixel coordinate. Every figure a
 * person reads comes from the server's string through `MoneyText`: labels, axis extremes, the data
 * table, any title text. That is why each extreme carries the ledger's own `Money` rather than the
 * number it was placed by.
 *
 * The vertical range runs from the lowest value to the highest, both lines together. A range
 * entirely below zero is stretched up to zero, so a shortfall reads against the line it crossed.
 * A range with no height at all - every value equal - is drawn level through the middle, and a
 * single point sits in the centre, rather than dividing by zero in either direction.
 *
 * @param points - the projection's step boundaries, in order. Never empty.
 * @param width - the plot's width, in SVG user units.
 * @param height - the plot's height, in SVG user units.
 * @returns every point's coordinates, the zero line's y when a value is negative, and where the
 *   highest and lowest values sit, each with the amount that holds it.
 */
export function scalePoints(
    points: readonly [BalancePoint, ...BalancePoint[]],
    width: number,
    height: number,
): ScaledChart {
    const [first] = points;
    const opening: Candidate = {
        amount: first.cash,
        line: 'cash',
        index: 0,
        value: Number(first.cash.amount),
    };
    let highest = opening;
    let lowest = opening;
    const values = points.map((point, index) => {
        const cash = Number(point.cash.amount);
        const invested = Number(point.invested.amount);
        for (const line of LINES) {
            const value = line === 'cash' ? cash : invested;
            if (value > highest.value) {
                highest = { amount: point[line], line, index, value };
            }
            if (value < lowest.value) {
                lowest = { amount: point[line], line, index, value };
            }
        }
        return { cash, invested };
    });

    const top = highest.value < 0 ? 0 : highest.value;
    const bottom = lowest.value;
    const span = top - bottom;
    const yOf = (value: number) => (span === 0 ? height / 2 : ((top - value) / span) * height);
    const lastIndex = points.length - 1;
    const xOf = (index: number) => (lastIndex === 0 ? width / 2 : (index / lastIndex) * width);
    const extreme = (candidate: Candidate): ChartExtreme => ({
        amount: candidate.amount,
        line: candidate.line,
        x: xOf(candidate.index),
        y: yOf(candidate.value),
    });

    return {
        points: values.map((value, index) => ({
            x: xOf(index),
            cashY: yOf(value.cash),
            investedY: yOf(value.invested),
        })),
        zeroY: bottom < 0 ? yOf(0) : undefined,
        highest: extreme(highest),
        lowest: extreme(lowest),
    };
}
