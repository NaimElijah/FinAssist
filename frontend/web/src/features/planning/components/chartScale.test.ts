// @vitest-environment node
// A pure function over plain data: no DOM needed.

import { describe, expect, it } from 'vitest';

import type { BalancePoint } from '../types/projection';
import { scalePoints } from './chartScale';

const WIDTH = 600;
const HEIGHT = 200;

/** A balance point on `date` with the cash and invested amounts given, in ILS. */
function point(date: string, cash: string, invested: string): BalancePoint {
    return {
        date,
        cash: { amount: cash, currency: 'ILS' },
        invested: { amount: invested, currency: 'ILS' },
        total: { amount: '0.00', currency: 'ILS' },
    };
}

describe('scalePoints', () => {
    it('Given_values_When_scaled_Then_a_higher_value_has_a_smaller_y_and_the_extremes_touch_the_edges', () => {
        const scaled = scalePoints(
            [
                point('2026-09-01', '100.00', '0.00'),
                point('2026-10-01', '200.00', '50.00'),
                point('2026-11-01', '300.00', '25.00'),
            ],
            WIDTH,
            HEIGHT,
        );

        const [first, second, third] = scaled.points;
        expect(first?.cashY).toBeGreaterThan(second?.cashY ?? Number.NaN);
        expect(second?.cashY).toBeGreaterThan(third?.cashY ?? Number.NaN);
        expect(second?.investedY).toBeLessThan(third?.investedY ?? Number.NaN);
        // 300 is the highest value, at the top edge; 0 the lowest, at the bottom.
        expect(third?.cashY).toBe(0);
        expect(first?.investedY).toBe(HEIGHT);
    });

    it('Given_three_points_When_scaled_Then_x_spreads_evenly_from_the_left_edge_to_the_right', () => {
        const scaled = scalePoints(
            [
                point('2026-09-01', '1.00', '1.00'),
                point('2026-10-01', '2.00', '2.00'),
                point('2026-11-01', '3.00', '3.00'),
            ],
            WIDTH,
            HEIGHT,
        );

        expect(scaled.points.map((scaledPoint) => scaledPoint.x)).toEqual([0, WIDTH / 2, WIDTH]);
    });

    it('Given_a_flat_series_When_scaled_Then_every_y_is_finite_and_level', () => {
        const scaled = scalePoints(
            [point('2026-09-01', '250.00', '250.00'), point('2026-10-01', '250.00', '250.00')],
            WIDTH,
            HEIGHT,
        );

        const ys = scaled.points.flatMap((scaledPoint) => [
            scaledPoint.cashY,
            scaledPoint.investedY,
        ]);
        expect(ys.every((y) => Number.isFinite(y))).toBe(true);
        expect(new Set(ys)).toEqual(new Set([HEIGHT / 2]));
    });

    it('Given_a_negative_range_When_scaled_Then_it_includes_zero_at_the_top_edge', () => {
        const scaled = scalePoints(
            [point('2026-09-01', '-100.00', '-80.00'), point('2026-10-01', '-50.00', '-20.00')],
            WIDTH,
            HEIGHT,
        );

        expect(scaled.zeroY).toBe(0);
        // The highest value, -20, sits below zero rather than at the top edge.
        expect(scaled.highest.y).toBeGreaterThan(0);
        expect(scaled.lowest.y).toBe(HEIGHT);
    });

    it('Given_a_range_crossing_zero_When_scaled_Then_the_zero_line_sits_between_the_edges', () => {
        const scaled = scalePoints(
            [point('2026-09-01', '-100.00', '0.00'), point('2026-10-01', '100.00', '0.00')],
            WIDTH,
            HEIGHT,
        );

        expect(scaled.zeroY).toBe(HEIGHT / 2);
    });

    it('Given_a_range_above_zero_When_scaled_Then_there_is_no_zero_line', () => {
        const scaled = scalePoints(
            [point('2026-09-01', '100.00', '10.00'), point('2026-10-01', '200.00', '20.00')],
            WIDTH,
            HEIGHT,
        );

        expect(scaled.zeroY).toBeUndefined();
    });

    it('Given_points_When_scaled_Then_each_extreme_carries_the_amount_that_holds_it_and_where_it_is', () => {
        const points = [
            point('2026-09-01', '100.00', '500.00'),
            point('2026-10-01', '-12500.00', '502.50'),
            point('2026-11-01', '40.00', '505.01'),
        ] as const;

        const scaled = scalePoints(points, WIDTH, HEIGHT);

        // The very object the ledger sent, so its label can only be the server's string.
        expect(scaled.highest).toEqual({
            amount: points[2].invested,
            line: 'invested',
            x: WIDTH,
            y: 0,
        });
        expect(scaled.highest.amount).toBe(points[2].invested);
        expect(scaled.lowest).toEqual({
            amount: points[1].cash,
            line: 'cash',
            x: WIDTH / 2,
            y: HEIGHT,
        });
        expect(scaled.lowest.amount).toBe(points[1].cash);
    });

    it('Given_a_single_point_When_scaled_Then_it_is_centred_rather_than_divided_by_zero', () => {
        const scaled = scalePoints([point('2026-09-01', '10.00', '20.00')], WIDTH, HEIGHT);

        expect(scaled.points.map((scaledPoint) => scaledPoint.x)).toEqual([WIDTH / 2]);
    });
});
