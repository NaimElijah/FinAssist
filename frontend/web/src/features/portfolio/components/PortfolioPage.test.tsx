// @vitest-environment jsdom
// Renders the portfolio lookup through the feature's own routes and drives its form, so it needs a
// DOM. fetch is faked, so any read the page starts is one the test planned for.

import { screen, waitFor } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { WriteAccess } from '@/shared/lib/writeAccess';
import {
    type FetchAnswer,
    fakeFetch,
    pendingResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { portfolioRoutes } from '../index';

const HOLDINGS_URL = '/assistant/api/v1/portfolio/holdings';

const NOT_ADVICE = 'These figures describe your own holdings and are not financial advice.';

/**
 * An assistant holding every read of `segment`'s holding in flight: the holding page's figures are
 * not under test here, only that the lookup reached it.
 */
function readsOf(segment: string): FetchAnswer {
    return (request) =>
        request.method === 'GET' && request.url.startsWith(`${HOLDINGS_URL}/${segment}/`)
            ? pendingResponse()
            : unexpectedRequest(request);
}

function renderPortfolio(access: WriteAccess = { canWrite: true }) {
    return renderRoutes(portfolioRoutes, '/portfolio', { access });
}

describe('PortfolioPage', () => {
    it('Given_the_portfolio_path_When_rendered_Then_it_names_the_area_and_says_the_figures_are_not_advice', async () => {
        fakeFetch(unexpectedRequest);

        renderPortfolio();

        expect(await screen.findByRole('heading', { level: 1, name: 'Portfolio' })).toBeDefined();
        expect(screen.getByText(NOT_ADVICE)).toBeDefined();
        expect(screen.getByLabelText('Symbol')).toHaveProperty('required', true);
    });

    it('Given_a_symbol_When_looked_up_Then_it_navigates_to_that_holding', async () => {
        fakeFetch(readsOf('VOO'));
        const { router } = renderPortfolio();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'VOO' })).toBeDefined();
        expect(router.state.location.pathname).toBe('/portfolio/VOO');
    });

    it('Given_a_read_only_session_When_a_symbol_is_looked_up_Then_the_lookup_is_offered_and_navigates', async () => {
        // A projection, a comparison and a lookup are reads: a viewer sees every area (ADR-032),
        // so hiding one behind useWriteAccess would take away what the role exists for.
        fakeFetch(readsOf('VOO'));
        const { router } = renderPortfolio({ canWrite: false });
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'VOO' })).toBeDefined();
        expect(router.state.location.pathname).toBe('/portfolio/VOO');
    });

    it('Given_a_lower_case_symbol_padded_with_spaces_When_looked_up_Then_the_URL_carries_it_trimmed_and_as_typed', async () => {
        // Upper-casing a symbol is the assistant's job; the URL shows what the owner typed.
        fakeFetch(readsOf('voo'));
        const { router } = renderPortfolio();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Symbol'), '  voo  ');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        await waitFor(() => {
            expect(router.state.location.pathname).toBe('/portfolio/voo');
        });
    });

    it('Given_a_symbol_containing_a_space_When_looked_up_Then_it_stays_one_encoded_path_segment', async () => {
        fakeFetch(readsOf('BRK%20B'));
        const { router } = renderPortfolio();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Symbol'), 'BRK B');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'BRK B' })).toBeDefined();
        expect(router.state.location.pathname).toBe('/portfolio/BRK%20B');
    });

    it('Given_a_symbol_of_only_spaces_When_looked_up_Then_native_validation_refuses_it_and_nothing_navigates', async () => {
        const requests = fakeFetch(unexpectedRequest);
        const { router } = renderPortfolio();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Symbol'), '   ');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(screen.getByLabelText('Symbol')).toHaveProperty('validity.patternMismatch', true);
        expect(router.state.location.pathname).toBe('/portfolio');
        expect(requests).toHaveLength(0);
    });
});
