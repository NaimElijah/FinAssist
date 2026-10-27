// @vitest-environment jsdom
// Drives the disposal calculator on a holding's page through the portfolio feature's own routes,
// so it needs a DOM. fetch is faked, so the feature's real api/ module and its mapping run under
// test.

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import type { WriteAccess } from '@/shared/lib/writeAccess';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    type RecordedRequest,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import type { FieldEdit } from '@/shared/testing/fieldEdits';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { portfolioRoutes } from '../index';

const HOLDINGS_URL = '/assistant/api/v1/portfolio/holdings';

const VOO_DISPOSAL_URL = `${HOLDINGS_URL}/VOO/disposal`;

/** One holding read or calculation: its symbol's path segment, then which one it is. */
const HOLDING_REQUEST = /^\/assistant\/api\/v1\/portfolio\/holdings\/([^/]+)\/([a-z]+)$/;

const CALCULATION_ONLY = 'Calculation only — nothing is recorded.';

const SECTION = 'Price a disposal';

const RESULT_HEADING = 'What this sale would realise';

const LOTS_CAPTION = 'Lots remaining after this disposal';

const DISPOSITION = {
    symbol: 'VOO',
    quantity: '4',
    proceeds: { amount: '2100.00', currency: 'USD' },
    costOfSale: { amount: '1600.00', currency: 'USD' },
    realisedGain: { amount: '500.00', currency: 'USD' },
    remaining: [
        {
            acquiredOn: '2024-03-01',
            quantity: '6',
            unitCost: { amount: '400.00', currency: 'USD' },
        },
        {
            acquiredOn: '2025-01-15',
            quantity: '2.5',
            unitCost: { amount: '450.50', currency: 'USD' },
        },
    ],
} as const;

/**
 * An assistant holding every symbol at a usable price in `currency`, pricing a disposal through
 * `disposal`, or refusing one as unplanned when none is given.
 */
function assistant(disposal?: FetchAnswer, currency = 'USD'): FetchAnswer {
    return (request) => {
        const [, symbol, resource] = HOLDING_REQUEST.exec(request.url) ?? [];
        const cost = { amount: '4200.00', currency };
        if (request.method === 'GET' && resource === 'position') {
            return jsonResponse({ symbol, quantity: '10.5', cost });
        }
        if (request.method === 'GET' && resource === 'valuation') {
            return jsonResponse({
                symbol,
                quantity: '10.5',
                cost,
                unitPrice: { amount: '500.00', currency },
                marketValue: { amount: '5250.00', currency },
                unrealisedGain: { amount: '1050.00', currency },
            });
        }
        if (request.method === 'POST' && resource === 'disposal' && disposal !== undefined) {
            return disposal(request);
        }
        return unexpectedRequest(request);
    };
}

function isDisposal(request: RecordedRequest): boolean {
    return request.method === 'POST' && request.url.endsWith('/disposal');
}

/**
 * Render a holding's page and wait for its position and valuation to show, so the calculator is
 * driven on a page no longer loading.
 *
 * `access` defaults to an owner's, matching `renderRoutes`'s own default, so a test that is not
 * about access passes nothing; the one test proving the disposal section stays offered to a
 * viewer passes `{canWrite: false}` deliberately.
 */
async function renderSettledHolding(
    path = '/portfolio/VOO',
    access: WriteAccess = { canWrite: true },
) {
    const rendered = renderRoutes(portfolioRoutes, path, { access });
    await screen.findByText('US$4,200.00');
    await screen.findByText('US$5,250.00');
    return rendered;
}

/**
 * Fill the calculator and submit it, leaving Currency as the calculator offers it. Typing itself is
 * not under test, so the fields are set.
 */
async function price(
    user: ReturnType<typeof userEvent.setup>,
    { quantity = '4', amount = '2100.00' }: { quantity?: string; amount?: string } = {},
) {
    fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: quantity } });
    fireEvent.change(screen.getByLabelText('Proceeds amount'), { target: { value: amount } });
    await user.click(screen.getByRole('button', { name: 'Price disposal' }));
}

/** The figure a description list pairs with `term`, inside the disposal section. */
function figure(term: string): string | null {
    const section = screen.getByRole('region', { name: SECTION });
    return (
        within(section).getByText(term, { selector: 'dt' }).nextElementSibling?.textContent ?? null
    );
}

/** Each body row's cell texts, in column order, the row header included. */
function rowsOf(table: HTMLElement): string[][] {
    return within(table)
        .getAllByRole('row')
        .slice(1)
        .map((row) => Array.from(row.querySelectorAll('th, td'), (cell) => cell.textContent));
}

/** The calculator's form. */
function disposalForm(): HTMLFormElement {
    const form = screen.getByLabelText('Quantity').closest('form');
    if (form === null) {
        throw new Error('the Quantity field is not inside a form');
    }
    return form;
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('DisposalSection', () => {
    it('Given_a_read_only_session_When_a_holding_page_renders_Then_the_disposal_section_is_still_offered', async () => {
        // Pricing a disposal is a calculation that records nothing, so the assistant grants it
        // under portfolio:read alone; a viewer must still be offered it (ADR-032).
        fakeFetch(assistant());

        await renderSettledHolding('/portfolio/VOO', { canWrite: false });

        expect(screen.getByRole('region', { name: SECTION })).toBeDefined();
        expect(screen.getByLabelText('Quantity')).toBeDefined();
        expect(screen.getByRole('button', { name: 'Price disposal' })).toBeDefined();
    });

    it('Given_a_holding_page_When_rendered_Then_the_disposal_section_says_it_is_a_calculation_that_records_nothing', async () => {
        fakeFetch(assistant());

        await renderSettledHolding();

        const section = screen.getByRole('region', { name: SECTION });
        const note = within(section).getByText(CALCULATION_ONLY);
        // The note describes the form, so it is read with the fields rather than skipped past.
        expect(disposalForm().getAttribute('aria-describedby')).toBe(note.id);
    });

    it('Given_a_quantity_and_proceeds_When_priced_Then_it_posts_the_body_and_shows_cost_of_sale_realised_gain_and_remaining_lots_exactly', async () => {
        const requests = fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        const table = await screen.findByRole('table', { name: LOTS_CAPTION });
        // Projected before comparing: RecordedRequest also carries headers, which this
        // assertion is not about, and toEqual demands an exact match on every field it is
        // given - unlike toMatchObject, which would stop checking body for an unplanned extra
        // field anywhere in its object graph.
        expect(
            requests.filter(isDisposal).map(({ method, url, body }) => ({ method, url, body })),
        ).toEqual([
            {
                method: 'POST',
                url: VOO_DISPOSAL_URL,
                body: { quantity: '4', proceeds: { amount: '2100.00', currency: 'USD' } },
            },
        ]);
        expect(figure('Cost of sale')).toBe('US$1,600.00');
        expect(figure('Realised gain')).toBe('US$500.00');
        // Oldest first, as received; a quantity is shown as sent, never formatted.
        expect(rowsOf(table)).toEqual([
            ['1 Mar 2024', '6', 'US$400.00'],
            ['15 Jan 2025', '2.5', 'US$450.50'],
        ]);
        expect(
            within(table)
                .getAllByRole('rowheader')
                .map((cell) => cell.textContent),
        ).toEqual(['1 Mar 2024', '15 Jan 2025']);
    });

    for (const currency of ['USD', 'GBP'] as const) {
        it(`Given_a_holding_whose_lots_are_in_${currency}_When_priced_without_choosing_a_currency_Then_the_proceeds_are_posted_in_${currency}`, async () => {
            // The assistant prices proceeds only in the lots' own currency and answers any other
            // with 422 CURRENCY_MISMATCH, so the calculator starts in the currency its lots cost.
            const requests = fakeFetch(assistant(() => jsonResponse(DISPOSITION), currency));
            renderRoutes(portfolioRoutes, '/portfolio/VOO');
            await screen.findByLabelText('Quantity');
            await screen.findByText('Market value');

            await price(userEvent.setup({ delay: null }));

            await screen.findByRole('table', { name: LOTS_CAPTION });
            expect(screen.getByLabelText('Currency')).toHaveProperty('value', currency);
            expect(requests.filter(isDisposal).map((request) => request.body)).toEqual([
                { quantity: '4', proceeds: { amount: '2100.00', currency } },
            ]);
        });
    }

    it('Given_the_position_still_loading_When_the_page_renders_Then_no_disposal_can_be_priced_yet', async () => {
        // Until the position loads there is no currency to start the proceeds in, and perhaps
        // nothing held to price at all.
        fakeFetch((request) =>
            request.url.endsWith('/position') ? pendingResponse() : assistant()(request),
        );
        renderRoutes(portfolioRoutes, '/portfolio/VOO');

        await screen.findByText('US$5,250.00');

        expect(screen.getByText('Loading position…')).toBeDefined();
        expect(screen.queryByRole('region', { name: SECTION })).toBeNull();
    });

    it('Given_a_disposal_priced_When_its_result_shows_Then_it_states_the_quantity_and_proceeds_it_priced', async () => {
        // Without them, the cost of sale and realised gain stand alone, and nothing on screen says
        // which inputs they answer.
        fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        await screen.findByRole('table', { name: LOTS_CAPTION });
        // A number of units, shown exactly as received; the proceeds formatted as money.
        expect(figure('Quantity')).toBe('4');
        expect(figure('Proceeds')).toBe('US$2,100.00');
    });

    it('Given_proceeds_below_the_cost_of_sale_When_priced_Then_the_realised_figure_reads_as_a_loss_with_its_sign_kept', async () => {
        fakeFetch(
            assistant(() =>
                jsonResponse({
                    ...DISPOSITION,
                    realisedGain: { amount: '-150.00', currency: 'USD' },
                }),
            ),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        await screen.findByText('-US$150.00');
        expect(figure('Realised loss')).toBe('-US$150.00');
        expect(screen.queryByText('Realised gain')).toBeNull();
    });

    it('Given_a_disposal_of_every_unit_held_When_priced_Then_an_empty_state_says_no_lots_remain', async () => {
        fakeFetch(assistant(() => jsonResponse({ ...DISPOSITION, remaining: [] })));
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        expect(await screen.findByText('No lots remaining after this disposal')).toBeDefined();
        expect(screen.queryByRole('table', { name: LOTS_CAPTION })).toBeNull();
    });

    it('Given_a_disposal_priced_When_its_result_shows_Then_focus_moves_to_the_result_heading', async () => {
        fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        const heading = await screen.findByRole('heading', { level: 3, name: RESULT_HEADING });
        await waitFor(() => {
            expect(document.activeElement).toBe(heading);
        });
    });

    it('Given_a_successful_disposal_When_priced_Then_no_query_is_invalidated_and_nothing_is_read_again', async () => {
        // Pricing records nothing, so no position, valuation or lot can have changed.
        const requests = fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
        const { queryClient } = await renderSettledHolding();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const requestsBefore = requests.length;

        await price(userEvent.setup({ delay: null }));

        await screen.findByRole('table', { name: LOTS_CAPTION });
        expect(invalidateSpy.mock.calls).toHaveLength(0);
        expect(
            requests.slice(requestsBefore).map((request) => `${request.method} ${request.url}`),
        ).toEqual([`POST ${VOO_DISPOSAL_URL}`]);
    });

    it('Given_more_units_than_are_held_When_priced_Then_the_server_detail_shows_in_an_error_state_before_the_form_and_takes_focus', async () => {
        const detail = 'Cannot dispose of 40 units of VOO: 10.5 are held.';
        fakeFetch(
            assistant(() => problemResponse({ status: 422, code: 'INSUFFICIENT_HOLDING', detail })),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }), { quantity: '40' });

        const section = screen.getByRole('region', { name: SECTION });
        const alert = await within(section).findByRole('alert');
        expect(within(alert).getByText(detail)).toBeDefined();
        expect(
            alert.compareDocumentPosition(disposalForm()) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
        expect(screen.queryByRole('table', { name: LOTS_CAPTION })).toBeNull();
    });

    it('Given_a_disposal_priced_When_a_later_calculation_is_refused_Then_the_error_shows_and_the_earlier_result_is_gone', async () => {
        // A result answers the calculation that produced it. Kept beside a later refusal, its
        // figures would read as the answer to the quantity that was refused.
        const detail = 'Cannot dispose of 40 units of VOO: 10.5 are held.';
        let refused = false;
        fakeFetch(
            assistant(() =>
                refused
                    ? problemResponse({ status: 422, code: 'INSUFFICIENT_HOLDING', detail })
                    : jsonResponse(DISPOSITION),
            ),
        );
        await renderSettledHolding();
        const user = userEvent.setup({ delay: null });
        await price(user);
        await screen.findByRole('table', { name: LOTS_CAPTION });

        refused = true;
        await price(user, { quantity: '40' });

        const section = screen.getByRole('region', { name: SECTION });
        const alert = await within(section).findByRole('alert');
        expect(within(alert).getByText(detail)).toBeDefined();
        expect(screen.queryByRole('table', { name: LOTS_CAPTION })).toBeNull();
        expect(screen.queryByText('Cost of sale')).toBeNull();
        expect(screen.queryByText('Realised gain')).toBeNull();
    });

    it('Given_the_assistant_refuses_the_pricing_as_FORBIDDEN_When_priced_Then_the_fixed_sentence_is_shown_instead_of_its_detail', async () => {
        // The server enforces access whatever the client hides (ADR-032), and a refusal never
        // tells the user which permission it was (ErrorState's fixed sentence).
        fakeFetch(
            assistant(() =>
                problemResponse({
                    status: 403,
                    code: 'FORBIDDEN',
                    detail: 'Role guest lacks portfolio:read.',
                }),
            ),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        const section = screen.getByRole('region', { name: SECTION });
        const alert = await within(section).findByRole('alert');
        expect(alert.textContent).toContain("You don't have permission to see this.");
        expect(alert.textContent).not.toContain('portfolio:read');
    });

    // Every field of the form, since each calls clearSettledOutcome on its own (frontend/web
    // CLAUDE.md: editing any field of a settled form clears its answer, and, for the disposal,
    // its result).
    const DISPOSAL_FIELD_EDITS = [
        { field: 'quantity', edit: (user) => user.type(screen.getByLabelText('Quantity'), '5') },
        {
            field: 'proceeds_amount',
            edit: (user) => user.type(screen.getByLabelText('Proceeds amount'), '0'),
        },
        {
            field: 'currency',
            edit: (user) => user.selectOptions(screen.getByLabelText('Currency'), 'ILS'),
        },
    ] satisfies readonly FieldEdit[];

    for (const { field, edit } of DISPOSAL_FIELD_EDITS) {
        it(`Given_a_disposal_priced_When_the_${field}_is_edited_Then_the_result_is_gone`, async () => {
            // A result answers the disposal it priced; beside inputs it never priced, its cost of
            // sale and realised gain would read as the answer for them.
            fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
            await renderSettledHolding();
            const user = userEvent.setup({ delay: null });
            await price(user);
            await screen.findByRole('table', { name: LOTS_CAPTION });

            await edit(user);

            expect(screen.queryByRole('heading', { level: 3, name: RESULT_HEADING })).toBeNull();
            expect(screen.queryByRole('table', { name: LOTS_CAPTION })).toBeNull();
        });
    }

    for (const { field, edit } of DISPOSAL_FIELD_EDITS) {
        it(`Given_a_refusal_shown_When_the_${field}_is_changed_Then_the_refusal_clears`, async () => {
            const detail = 'Cannot dispose of 40 units of VOO: 10.5 are held.';
            fakeFetch(
                assistant(() =>
                    problemResponse({ status: 422, code: 'INSUFFICIENT_HOLDING', detail }),
                ),
            );
            await renderSettledHolding();
            const user = userEvent.setup({ delay: null });
            await price(user, { quantity: '40' });
            const section = screen.getByRole('region', { name: SECTION });
            await within(section).findByRole('alert');

            await edit(user);

            expect(within(section).queryByRole('alert')).toBeNull();
        });
    }

    it('Given_a_quantity_the_assistant_refuses_as_non_positive_When_priced_Then_the_RULE_VIOLATED_detail_shows_in_an_error_state', async () => {
        const detail = 'A disposal must be of a positive quantity.';
        fakeFetch(assistant(() => problemResponse({ status: 422, code: 'RULE_VIOLATED', detail })));
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }), { quantity: '0' });

        const section = screen.getByRole('region', { name: SECTION });
        const alert = await within(section).findByRole('alert');
        expect(within(alert).getByText(detail)).toBeDefined();
        expect(screen.getByLabelText('Quantity').getAttribute('aria-invalid')).toBeNull();
    });

    it('Given_a_malformed_quantity_When_priced_Then_its_400_field_error_lands_on_Quantity_and_takes_focus', async () => {
        const message = "String should match pattern '^-?\\d+(\\.\\d+)?$'";
        fakeFetch(
            assistant(() =>
                problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'The request could not be read; each entry in errors names why.',
                    errors: [{ field: 'quantity', message }],
                }),
            ),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }), { quantity: 'four' });

        const error = await screen.findByText(message);
        const quantity = screen.getByLabelText('Quantity');
        expect(quantity.getAttribute('aria-invalid')).toBe('true');
        expect(quantity.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        expect(screen.getByLabelText('Proceeds amount').getAttribute('aria-invalid')).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(quantity);
        });
    });

    it('Given_a_malformed_proceeds_amount_When_priced_Then_its_400_field_error_lands_on_Proceeds_amount', async () => {
        const message = "String should match pattern '^-?\\d+(\\.\\d+)?$'";
        fakeFetch(
            assistant(() =>
                problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'The request could not be read; each entry in errors names why.',
                    errors: [{ field: 'proceeds.amount', message }],
                }),
            ),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }), { amount: '2,100' });

        const error = await screen.findByText(message);
        const amount = screen.getByLabelText('Proceeds amount');
        expect(amount.getAttribute('aria-invalid')).toBe('true');
        expect(amount.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        expect(screen.getByLabelText('Quantity').getAttribute('aria-invalid')).toBeNull();
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(amount);
        });
    });

    it('Given_a_400_naming_a_field_the_form_cannot_place_When_priced_Then_the_whole_problem_is_an_error_state', async () => {
        // Placing only the errors the form recognises would silently drop the rest.
        fakeFetch(
            assistant(() =>
                problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'The request could not be read; each entry in errors names why.',
                    errors: [
                        { field: 'quantity', message: 'Field required' },
                        { field: 'proceeds', message: 'Field required' },
                    ],
                }),
            ),
        );
        await renderSettledHolding();

        await price(userEvent.setup({ delay: null }));

        const section = screen.getByRole('region', { name: SECTION });
        const alert = await within(section).findByRole('alert');
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['quantity: Field required', 'proceeds: Field required']);
        expect(screen.getByLabelText('Quantity').getAttribute('aria-invalid')).toBeNull();
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard that reads render-time state lets both through.
        const requests = fakeFetch(assistant(() => pendingResponse()));
        await renderSettledHolding();
        fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '4' } });
        fireEvent.change(screen.getByLabelText('Proceeds amount'), {
            target: { value: '2100.00' },
        });

        fireEvent.submit(disposalForm());
        fireEvent.submit(disposalForm());

        // By the time pending has rendered, every request either submit started has reached fetch.
        const submit = screen.getByRole('button', { name: 'Price disposal' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        expect(requests.filter(isDisposal)).toHaveLength(1);
    });

    it('Given_a_disposal_priced_for_VOO_When_the_page_moves_back_to_BND_already_read_Then_neither_its_result_nor_its_inputs_carry_over', async () => {
        // BND was read first, so its position is cached and the calculator shows at once on the
        // way back: nothing but its key starts it afresh.
        fakeFetch(assistant(() => jsonResponse(DISPOSITION)));
        const { router, queryClient } = await renderSettledHolding('/portfolio/BND');
        await act(() => router.navigate('/portfolio/VOO'));
        await screen.findByRole('heading', { level: 1, name: 'VOO' });
        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
        await price(userEvent.setup({ delay: null }));
        await screen.findByRole('table', { name: LOTS_CAPTION });

        await act(() => router.navigate('/portfolio/BND'));

        expect(await screen.findByRole('heading', { level: 1, name: 'BND' })).toBeDefined();
        expect(screen.queryByText('Cost of sale')).toBeNull();
        expect(screen.queryByRole('table', { name: LOTS_CAPTION })).toBeNull();
        expect(screen.getByLabelText('Quantity')).toHaveProperty('value', '');
        expect(screen.getByLabelText('Proceeds amount')).toHaveProperty('value', '');
        // BND's background refetch settles before the test ends.
        await waitFor(() => {
            expect(queryClient.isFetching()).toBe(0);
        });
    });
});
