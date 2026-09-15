// @vitest-environment jsdom
// Renders a holding's page through the portfolio feature's own routes and drives its lookup, so it
// needs a DOM. fetch is faked, so the feature's real api/ module and its mapping run under test.

import { screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    type RecordedRequest,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { portfolioKeys } from '../hooks/portfolioQueries';
import { portfolioRoutes } from '../index';

const HOLDINGS_URL = '/assistant/api/v1/portfolio/holdings';

const NOT_ADVICE = 'These figures describe your own holdings and are not financial advice.';

const NO_PRICE_MESSAGE =
    'No usable price right now — a valuation needs a quote no more than an hour old';

/** A quantity with more digits than a thousands separator would leave alone, had it been formatted. */
const POSITION = {
    symbol: 'VOO',
    quantity: '1234.125',
    cost: { amount: '4321.50', currency: 'USD' },
} as const;

const VALUATION = {
    ...POSITION,
    unitPrice: { amount: '512.34', currency: 'USD' },
    marketValue: { amount: '5187.44', currency: 'USD' },
    unrealisedGain: { amount: '865.94', currency: 'USD' },
} as const;

const NOTHING_HELD = {
    status: 404,
    code: 'HOLDING_NOT_FOUND',
    detail: 'Nothing is held under VOO.',
} as const;

type Answer = () => Response | Promise<Response>;

type Read = 'position' | 'valuation';

/** An assistant answering the reads of `segment`'s holding through `answers`, refusing the rest. */
function assistant(
    answers: { readonly position: Answer; readonly valuation: Answer },
    segment = 'VOO',
): FetchAnswer {
    return (request) => {
        if (request.method === 'GET' && request.url === `${HOLDINGS_URL}/${segment}/position`) {
            return answers.position();
        }
        if (request.method === 'GET' && request.url === `${HOLDINGS_URL}/${segment}/valuation`) {
            return answers.valuation();
        }
        return unexpectedRequest(request);
    };
}

/** An assistant holding VOO, with a usable price. */
const PRICED = assistant({
    position: () => jsonResponse(POSITION),
    valuation: () => jsonResponse(VALUATION),
});

function isRead(request: RecordedRequest, read: Read): boolean {
    return request.url === `${HOLDINGS_URL}/VOO/${read}`;
}

/** The figure a description list pairs with `term`, inside the section headed `section`. */
function figure(section: string, term: string): string | null {
    const region = screen.getByRole('region', { name: section });
    const label = within(region).getByText(term, { selector: 'dt' });
    return label.nextElementSibling?.textContent ?? null;
}

function renderHolding(path = '/portfolio/VOO') {
    return renderRoutes(portfolioRoutes, path);
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('HoldingPage', () => {
    it('Given_a_position_and_a_valuation_When_the_page_renders_Then_every_figure_shows_its_exact_string', async () => {
        fakeFetch(PRICED);

        renderHolding();

        expect(await screen.findByRole('heading', { level: 1, name: 'VOO' })).toBeDefined();
        await screen.findByText('1234.125');
        await screen.findByText('US$5,187.44');
        expect(figure('Position', 'Quantity')).toBe('1234.125');
        expect(figure('Position', 'Cost')).toBe('US$4,321.50');
        expect(figure('Valuation', 'Unit price')).toBe('US$512.34');
        expect(figure('Valuation', 'Market value')).toBe('US$5,187.44');
        expect(figure('Valuation', 'Unrealised gain')).toBe('US$865.94');
        expect(screen.getByText(NOT_ADVICE)).toBeDefined();
        expect(screen.getByRole('link', { name: 'Back to Portfolio' }).getAttribute('href')).toBe(
            '/portfolio',
        );
    });

    it('Given_a_negative_unrealised_gain_When_the_page_renders_Then_it_reads_as_a_loss_with_its_sign_kept', async () => {
        fakeFetch(
            assistant({
                position: () => jsonResponse(POSITION),
                valuation: () =>
                    jsonResponse({
                        ...VALUATION,
                        unrealisedGain: { amount: '-865.94', currency: 'USD' },
                    }),
            }),
        );

        renderHolding();

        await screen.findByText('-US$865.94');
        expect(figure('Valuation', 'Unrealised loss')).toBe('-US$865.94');
        expect(screen.queryByText('Unrealised gain')).toBeNull();
    });

    it('Given_a_zero_unrealised_gain_When_the_page_renders_Then_it_reads_as_a_gain', async () => {
        fakeFetch(
            assistant({
                position: () => jsonResponse(POSITION),
                valuation: () =>
                    jsonResponse({
                        ...VALUATION,
                        unrealisedGain: { amount: '0.00', currency: 'USD' },
                    }),
            }),
        );

        renderHolding();

        await screen.findByText('US$0.00');
        expect(figure('Valuation', 'Unrealised gain')).toBe('US$0.00');
        expect(screen.queryByText('Unrealised loss')).toBeNull();
    });

    it('Given_both_reads_in_flight_When_the_page_renders_Then_each_section_says_it_is_loading', async () => {
        fakeFetch(assistant({ position: pendingResponse, valuation: pendingResponse }));

        renderHolding();

        expect(await screen.findByText('Loading position…')).toBeDefined();
        expect(screen.getByText('Loading valuation…')).toBeDefined();
    });

    it('Given_nothing_held_When_the_page_renders_Then_it_says_so_calmly_and_shows_no_valuation', async () => {
        fakeFetch(
            assistant({
                position: () => problemResponse(NOTHING_HELD),
                valuation: () => problemResponse(NOTHING_HELD),
            }),
        );

        const { queryClient } = renderHolding();

        expect(await screen.findByText('Nothing is held under VOO')).toBeDefined();
        // The valuation answers 404 too; wait for it, so a card for it would have rendered by now.
        await waitFor(() => {
            expect(queryClient.getQueryState(portfolioKeys.valuation('VOO'))?.status).toBe('error');
        });
        expect(screen.queryByRole('heading', { name: 'Valuation' })).toBeNull();
        // With nothing held, there is nothing to price a disposal of.
        expect(screen.queryByRole('heading', { name: 'Price a disposal' })).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    });

    it('Given_nothing_held_and_the_valuation_answering_before_the_position_When_the_page_renders_Then_no_error_or_retry_is_shown', async () => {
        // Without the development seed both reads answer 404, and either may land first. A
        // valuation finding nothing held is a considered answer, not a failure to retry.
        fakeFetch(
            assistant({
                position: pendingResponse,
                valuation: () => problemResponse(NOTHING_HELD),
            }),
        );

        renderHolding();

        const valuation = await screen.findByRole('region', { name: 'Valuation' });
        await waitFor(() => {
            expect(within(valuation).queryByText('Loading valuation…')).toBeNull();
        });
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
        expect(within(valuation).getByText('Nothing is held under VOO')).toBeDefined();
        expect(screen.getByText('Loading position…')).toBeDefined();
    });

    it('Given_no_usable_price_When_the_page_renders_Then_the_no_price_state_shows_while_the_position_still_renders', async () => {
        fakeFetch(
            assistant({
                position: () => jsonResponse(POSITION),
                valuation: () =>
                    problemResponse({
                        status: 503,
                        code: 'PRICE_UNAVAILABLE',
                        detail: 'No price recent enough to value this holding is available.',
                    }),
            }),
        );

        renderHolding();

        const valuation = await screen.findByRole('region', { name: 'Valuation' });
        expect(await within(valuation).findByText(NO_PRICE_MESSAGE)).toBeDefined();
        await screen.findByText('1234.125');
        expect(figure('Position', 'Quantity')).toBe('1234.125');
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    });

    it('Given_the_position_read_fails_otherwise_When_Try_again_is_clicked_Then_it_is_read_again_and_shown', async () => {
        let failing = true;
        const requests = fakeFetch(
            assistant({
                position: () =>
                    failing
                        ? problemResponse({
                              status: 500,
                              code: 'INTERNAL_ERROR',
                              detail: 'The assistant failed to handle this request.',
                          })
                        : jsonResponse(POSITION),
                valuation: () => jsonResponse(VALUATION),
            }),
        );

        renderHolding();

        const position = await screen.findByRole('region', { name: 'Position' });
        const alert = await within(position).findByRole('alert');
        expect(
            within(alert).getByText('The assistant failed to handle this request.'),
        ).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('1234.125')).toBeDefined();
        await waitFor(() => {
            expect(screen.queryByRole('alert')).toBeNull();
        });
        expect(requests.filter((request) => isRead(request, 'position'))).toHaveLength(2);
    });

    it('Given_the_valuation_read_fails_otherwise_When_the_page_renders_Then_an_error_state_offers_a_retry_beside_the_position', async () => {
        fakeFetch(
            assistant({
                position: () => jsonResponse(POSITION),
                valuation: () =>
                    problemResponse({
                        status: 500,
                        code: 'INTERNAL_ERROR',
                        detail: 'The assistant failed to handle this request.',
                    }),
            }),
        );

        renderHolding();

        const valuation = await screen.findByRole('region', { name: 'Valuation' });
        const alert = await within(valuation).findByRole('alert');
        expect(
            within(alert).getByText('The assistant failed to handle this request.'),
        ).toBeDefined();
        expect(within(alert).getByRole('button', { name: 'Try again' })).toBeDefined();
        await screen.findByText('1234.125');
        expect(figure('Position', 'Cost')).toBe('US$4,321.50');
    });

    it('Given_a_symbol_containing_a_space_in_the_URL_When_the_page_renders_Then_its_heading_shows_it_and_its_reads_travel_encoded', async () => {
        const requests = fakeFetch(
            assistant(
                {
                    position: () => jsonResponse({ ...POSITION, symbol: 'BRK B' }),
                    valuation: () => jsonResponse({ ...VALUATION, symbol: 'BRK B' }),
                },
                'BRK%20B',
            ),
        );

        renderHolding('/portfolio/BRK%20B');

        expect(await screen.findByRole('heading', { level: 1, name: 'BRK B' })).toBeDefined();
        await screen.findByText('US$5,187.44');
        expect(requests.map((request) => request.url).sort()).toEqual([
            `${HOLDINGS_URL}/BRK%20B/position`,
            `${HOLDINGS_URL}/BRK%20B/valuation`,
        ]);
    });

    it('Given_a_holding_shown_When_its_symbol_is_looked_up_again_Then_its_position_and_valuation_are_read_again_and_shown', async () => {
        // The same symbol is the same URL and the same query keys, and refetching on window focus
        // is off, so without an explicit refresh the owner could never see a newer figure.
        let position: unknown = POSITION;
        let valuation: unknown = VALUATION;
        const requests = fakeFetch(
            assistant({
                position: () => jsonResponse(position),
                valuation: () => jsonResponse(valuation),
            }),
        );
        renderHolding();
        await screen.findByText('1234.125');
        await screen.findByText('US$5,187.44');

        position = { ...POSITION, quantity: '1300' };
        valuation = {
            ...VALUATION,
            quantity: '1300',
            marketValue: { amount: '6660.20', currency: 'USD' },
        };
        const user = userEvent.setup({ delay: null });
        await user.type(screen.getByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(await screen.findByText('1300')).toBeDefined();
        expect(await screen.findByText('US$6,660.20')).toBeDefined();
        expect(requests.filter((request) => isRead(request, 'position'))).toHaveLength(2);
        expect(requests.filter((request) => isRead(request, 'valuation'))).toHaveLength(2);
    });

    it('Given_another_holding_cached_When_the_symbol_shown_is_looked_up_again_Then_only_that_holdings_reads_are_invalidated', async () => {
        // A key prefix matches element by element, so refreshing VOO must not reach VOOG's reads.
        fakeFetch(PRICED);
        const { queryClient } = renderHolding();
        await screen.findByText('1234.125');
        await screen.findByText('US$5,187.44');
        queryClient.setQueryData(portfolioKeys.position('VOOG'), { ...POSITION, symbol: 'VOOG' });
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });

        await user.type(screen.getByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
        expect(invalidateSpy.mock.calls.map(([options]) => options?.queryKey)).toEqual([
            portfolioKeys.holding('VOO'),
        ]);
        expect(queryClient.getQueryState(portfolioKeys.position('VOOG'))?.isInvalidated).toBe(
            false,
        );
    });

    it('Given_a_position_shown_When_its_read_again_fails_otherwise_Then_its_figures_stay_beside_the_error', async () => {
        // TanStack Query keeps a query's data when a later read fails, and the owner keeps seeing
        // what was there rather than having it replaced by the failure.
        let failing = false;
        fakeFetch(
            assistant({
                position: () =>
                    failing
                        ? problemResponse({
                              status: 500,
                              code: 'INTERNAL_ERROR',
                              detail: 'The assistant failed to handle this request.',
                          })
                        : jsonResponse(POSITION),
                valuation: () => jsonResponse(VALUATION),
            }),
        );
        const { queryClient } = renderHolding();
        await screen.findByText('1234.125');
        await screen.findByText('US$5,187.44');

        failing = true;
        const user = userEvent.setup({ delay: null });
        await user.type(screen.getByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        const position = screen.getByRole('region', { name: 'Position' });
        const alert = await within(position).findByRole('alert');
        expect(
            within(alert).getByText('The assistant failed to handle this request.'),
        ).toBeDefined();
        expect(figure('Position', 'Quantity')).toBe('1234.125');
        expect(figure('Position', 'Cost')).toBe('US$4,321.50');
        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
    });

    it('Given_a_valuation_shown_When_its_read_again_finds_no_usable_price_Then_it_hides_and_the_no_price_state_shows', async () => {
        // A valuation kept from before the price went stale is what the 503 refuses to give.
        let priced = true;
        fakeFetch(
            assistant({
                position: () => jsonResponse(POSITION),
                valuation: () =>
                    priced
                        ? jsonResponse(VALUATION)
                        : problemResponse({
                              status: 503,
                              code: 'PRICE_UNAVAILABLE',
                              detail: 'No price recent enough to value this holding is available.',
                          }),
            }),
        );
        const { queryClient } = renderHolding();
        await screen.findByText('1234.125');
        await screen.findByText('US$5,187.44');

        priced = false;
        const user = userEvent.setup({ delay: null });
        await user.type(screen.getByLabelText('Symbol'), 'VOO');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        const valuation = screen.getByRole('region', { name: 'Valuation' });
        expect(await within(valuation).findByText(NO_PRICE_MESSAGE)).toBeDefined();
        expect(within(valuation).queryByText('Market value')).toBeNull();
        expect(within(valuation).queryByText('US$5,187.44')).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
        expect(figure('Position', 'Quantity')).toBe('1234.125');
        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
    });

    it('Given_another_symbol_looked_up_from_a_holding_When_its_page_opens_Then_the_lookup_starts_empty', async () => {
        // The lookup belongs to the holding on screen, so text typed on one holding's page must not
        // follow the owner onto the next.
        const BND = assistant(
            {
                position: () => jsonResponse({ ...POSITION, symbol: 'BND' }),
                valuation: () => jsonResponse({ ...VALUATION, symbol: 'BND' }),
            },
            'BND',
        );
        fakeFetch((request) =>
            request.url.startsWith(`${HOLDINGS_URL}/BND/`) ? BND(request) : PRICED(request),
        );
        const { router, queryClient } = renderHolding();
        await screen.findByText('1234.125');
        const user = userEvent.setup({ delay: null });

        await user.type(screen.getByLabelText('Symbol'), 'BND');
        await user.click(screen.getByRole('button', { name: 'Look up' }));

        expect(await screen.findByRole('heading', { level: 1, name: 'BND' })).toBeDefined();
        expect(router.state.location.pathname).toBe('/portfolio/BND');
        expect(screen.getByLabelText('Symbol')).toHaveProperty('value', '');
        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
    });
});
