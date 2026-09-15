// @vitest-environment jsdom
// Renders SVG and reads its attributes, so it needs a DOM.

import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { BalancePoint, Projection } from '../types/projection';
import { ProjectionChart } from './ProjectionChart';

/** A balance point on `date`, its amounts in ILS exactly as given. */
function point(date: string, cash: string, invested: string, total: string): BalancePoint {
    return {
        date,
        cash: { amount: cash, currency: 'ILS' },
        invested: { amount: invested, currency: 'ILS' },
        total: { amount: total, currency: 'ILS' },
    };
}

/**
 * Four monthly points whose cash dips below zero at the second, and whose invested line ends on an
 * amount a double cannot hold: through `Number` it would read ...568.00, not ...567.89.
 */
const WITH_SHORTFALL: Projection = {
    scenario: 's1',
    horizon: { from: '2026-09-01', to: '2026-12-01' },
    step: 'MONTHLY',
    currency: 'ILS',
    points: [
        point('2026-09-01', '100.00', '500.00', '600.00'),
        point('2026-10-01', '-12500.00', '502.50', '-11997.50'),
        point('2026-11-01', '40.00', '505.01', '545.01'),
        point('2026-12-01', '60.00', '12345678901234567.89', '12345678901234627.89'),
    ],
    firstShortfall: '2026-10-01',
};

/** The same horizon with every value above zero, and no shortfall. */
const WITHOUT_SHORTFALL: Projection = {
    scenario: 's1',
    horizon: { from: '2026-09-01', to: '2026-11-01' },
    step: 'MONTHLY',
    currency: 'ILS',
    points: [
        point('2026-09-01', '100.00', '500.00', '600.00'),
        point('2026-10-01', '150.00', '502.50', '652.50'),
        point('2026-11-01', '200.00', '505.01', '705.01'),
    ],
};

/** The chart's one `role="img"` SVG. */
function chartImage(): SVGSVGElement {
    const image = screen.getByRole('img');
    if (!(image instanceof SVGSVGElement)) {
        throw new Error('the chart image is not an <svg>');
    }
    return image;
}

/** The chart's path for `line`, failing loudly when it is absent. */
function linePath(line: 'cash' | 'invested'): SVGPathElement {
    const path = chartImage().querySelector<SVGPathElement>(`path[data-line="${line}"]`);
    if (path === null) {
        throw new Error(`the chart draws no ${line} path`);
    }
    return path;
}

/** How many vertices a path's `d` moves or draws to. */
function vertexCount(path: SVGPathElement): number {
    return path.getAttribute('d')?.match(/[ML]/g)?.length ?? 0;
}

/** The value label `name` ("Highest" or "Lowest") and what it shows. */
function extremeLabel(name: string): HTMLElement {
    const term = screen.getByText(name);
    const definition = term.nextElementSibling;
    if (!(definition instanceof HTMLElement)) {
        throw new Error(`the ${name} label has no value beside it`);
    }
    return definition;
}

describe('ProjectionChart', () => {
    it('Given_four_points_When_charted_Then_it_draws_two_paths_each_with_four_vertices', () => {
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        expect(chartImage().querySelectorAll('path')).toHaveLength(2);
        expect(vertexCount(linePath('cash'))).toBe(4);
        expect(vertexCount(linePath('invested'))).toBe(4);
    });

    it('Given_the_two_lines_When_charted_Then_invested_is_dashed_and_cash_is_solid', () => {
        // Told apart by stroke style as well as colour, so the lines never rely on colour alone.
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        expect(linePath('cash').getAttribute('stroke-dasharray')).toBeNull();
        expect(linePath('invested').getAttribute('stroke-dasharray')).not.toBeNull();
    });

    it('Given_a_chart_When_rendered_Then_the_image_is_labelled_by_its_title_and_description', () => {
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        const image = chartImage();
        const title = image.querySelector('title');
        const description = image.querySelector('desc');
        expect(image.getAttribute('aria-labelledby')?.split(' ')).toEqual([
            title?.id,
            description?.id,
        ]);
        expect(title?.textContent).toBe('Projected cash and invested balances');
    });

    it('Given_a_first_shortfall_When_charted_Then_a_marker_stands_at_the_step_it_names', () => {
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        const marker = chartImage().querySelector('[data-marker="first-shortfall"]');
        // The second point's x: the first move is to the first point, the next line to the second.
        const secondVertex = /L\s*([\d.]+)/.exec(linePath('cash').getAttribute('d') ?? '');
        expect(marker?.getAttribute('x1')).toBe(secondVertex?.[1]);
        expect(screen.getByRole('list', { name: 'Legend' }).textContent).toContain(
            'First shortfall',
        );
    });

    it('Given_no_shortfall_When_charted_Then_no_marker_is_drawn_or_listed', () => {
        render(<ProjectionChart projection={WITHOUT_SHORTFALL} />);

        expect(chartImage().querySelector('[data-marker="first-shortfall"]')).toBeNull();
        expect(screen.getByRole('list', { name: 'Legend' }).textContent).not.toContain(
            'First shortfall',
        );
    });

    it('Given_a_value_below_zero_When_charted_Then_a_zero_line_is_drawn', () => {
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        expect(chartImage().querySelector('[data-marker="zero"]')).not.toBeNull();
    });

    it('Given_every_value_above_zero_When_charted_Then_no_zero_line_is_drawn', () => {
        render(<ProjectionChart projection={WITHOUT_SHORTFALL} />);

        expect(chartImage().querySelector('[data-marker="zero"]')).toBeNull();
    });

    it('Given_extremes_When_charted_Then_their_labels_show_the_exact_server_strings', () => {
        render(<ProjectionChart projection={WITH_SHORTFALL} />);

        const highest = extremeLabel('Highest');
        const lowest = extremeLabel('Lowest');
        // Formatted from the string itself: a float re-formatted would end ...568.00.
        expect(within(highest).getByText('₪12,345,678,901,234,567.89').getAttribute('value')).toBe(
            '12345678901234567.89',
        );
        expect(highest.textContent).toContain('invested');
        expect(within(lowest).getByText('-₪12,500.00').getAttribute('value')).toBe('-12500.00');
        expect(lowest.textContent).toContain('cash');
    });

    it('Given_a_horizon_When_charted_Then_its_start_and_end_dates_are_labelled', () => {
        const { container } = render(<ProjectionChart projection={WITH_SHORTFALL} />);

        const dates = [...container.querySelectorAll('time')].map((time) =>
            time.getAttribute('datetime'),
        );
        expect(dates).toEqual(['2026-09-01', '2026-12-01']);
    });
});
