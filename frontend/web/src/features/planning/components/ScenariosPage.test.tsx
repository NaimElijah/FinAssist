// @vitest-environment jsdom
// Renders the planning routes through the feature's own route table, so it needs a DOM. fetch is
// faked, so the feature's real api/ module and its mapping run under test.

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    type FetchAnswer,
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { planKeys } from '../hooks/planQueries';
import { planningRoutes } from '../index';

const FLOWS_URL = '/ledger/api/v1/planned-flows';
const SCENARIOS_URL = '/ledger/api/v1/scenarios';

const RENT_ID = 'b1b1b1b1-4d8c-4f3a-9a61-2f1c5e0d9b11';
const SALARY_ID = 'a1a1a1a1-4d8c-4f3a-9a61-2f1c5e0d9b11';

/** A planned flow in ILS. */
const RENT_ILS = {
    id: RENT_ID,
    description: 'Rent',
    amount: { amount: '250.00', currency: 'ILS' },
    direction: 'OUTFLOW',
    startsOn: '2026-09-01',
    recurrence: { kind: 'ONCE' },
    state: { status: 'PLANNED' },
} as const;

/** A planned flow in USD, in a different currency from `RENT_ILS`. */
const SALARY_USD = {
    id: SALARY_ID,
    description: 'Salary',
    amount: { amount: '1000.00', currency: 'USD' },
    direction: 'INFLOW',
    startsOn: '2026-09-01',
    recurrence: { kind: 'ONCE' },
    state: { status: 'COMMITTED', on: '2026-08-15' },
} as const;

const BASELINE = {
    id: 's1',
    name: 'Baseline',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0',
} as const;

const AGGRESSIVE = {
    id: 's2',
    name: 'Aggressive saving',
    currency: 'ILS',
    flows: [RENT_ID],
    growthRatePerStep: '0.005',
} as const;

/** The table row a scenario's name heads. */
function rowOf(name: string): HTMLElement {
    const row = screen.getByRole('rowheader', { name }).closest('tr');
    if (row === null) {
        throw new Error(`no table row is headed by ${name}`);
    }
    return row;
}

/**
 * A ledger holding `flows` and `scenarios`, both fixed; `overrides`, when given, answers anything
 * else, such as a define POST.
 */
function ledger(
    flows: readonly unknown[],
    scenarios: readonly unknown[],
    overrides?: FetchAnswer,
): FetchAnswer {
    return (request) => {
        if (request.url === FLOWS_URL && request.method === 'GET') return jsonResponse(flows);
        if (request.url === SCENARIOS_URL && request.method === 'GET')
            return jsonResponse(scenarios);
        if (overrides !== undefined) return overrides(request);
        return unexpectedRequest(request);
    };
}

function renderScenarios() {
    return renderRoutes(planningRoutes, '/planning/scenarios');
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('ScenariosPage', () => {
    it('Given_scenarios_served_in_an_order_When_the_page_renders_Then_rows_show_the_growth_rate_string_exactly', async () => {
        fakeFetch(ledger([], [BASELINE, AGGRESSIVE]));

        renderScenarios();

        await screen.findByRole('table', { name: 'Scenarios' });
        expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
            'Baseline',
            'Aggressive saving',
        ]);
        // Column order: Currency, Flows, Growth per step.
        const baselineCells = within(rowOf('Baseline')).getAllByRole('cell');
        expect(baselineCells.map((cell) => cell.textContent)).toEqual(['ILS', '0', '0']);
        const aggressiveCells = within(rowOf('Aggressive saving')).getAllByRole('cell');
        // The exact string the ledger sent, never converted to a percentage (ADR-029).
        expect(aggressiveCells.map((cell) => cell.textContent)).toEqual(['ILS', '1', '0.005']);
    });

    it('Given_a_scenarios_flow_count_When_rendered_Then_it_shows_the_number_of_flow_ids', async () => {
        fakeFetch(ledger([], [BASELINE, AGGRESSIVE]));

        renderScenarios();

        await screen.findByRole('table', { name: 'Scenarios' });
        // Column order: Currency, Flows, Growth per step.
        expect(within(rowOf('Baseline')).getAllByRole('cell').at(1)?.textContent).toBe('0');
        expect(within(rowOf('Aggressive saving')).getAllByRole('cell').at(1)?.textContent).toBe(
            '1',
        );
    });

    it('Given_no_scenarios_When_the_page_renders_Then_the_empty_state_names_the_do_nothing_baseline', async () => {
        fakeFetch(ledger([], []));

        renderScenarios();

        expect(
            await screen.findByText(
                'No scenarios yet — the do-nothing baseline is a scenario with no flows',
            ),
        ).toBeDefined();
        expect(screen.queryByRole('table')).toBeNull();
    });

    it('Given_the_list_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url === FLOWS_URL && request.method === 'GET') return jsonResponse([]);
            if (request.url !== SCENARIOS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({ status: 503, code: 'UNAVAILABLE', detail: 'Try later.' })
                : jsonResponse([]);
        });

        renderScenarios();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Try later.')).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(
            await screen.findByText(
                'No scenarios yet — the do-nothing baseline is a scenario with no flows',
            ),
        ).toBeDefined();
        expect(requests.filter((request) => request.url === SCENARIOS_URL)).toHaveLength(2);
    });
});

describe('ScenariosPage define-scenario form', () => {
    it('Given_flows_in_two_currencies_When_ILS_is_the_chosen_currency_Then_only_the_ILS_flow_is_offered', async () => {
        fakeFetch(ledger([RENT_ILS, SALARY_USD], []));
        renderScenarios();
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        expect(await screen.findByLabelText(/Rent/)).toBeDefined();
        expect(screen.queryByLabelText(/Salary/)).toBeNull();
    });

    it('Given_a_flow_checkbox_When_shown_Then_its_label_names_the_description_amount_direction_and_state', async () => {
        fakeFetch(ledger([RENT_ILS], []));
        renderScenarios();
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        const checkbox = await screen.findByLabelText(/Rent/);
        const label = checkbox.closest('label');
        expect(label?.textContent).toContain('Rent');
        expect(label?.textContent).toContain('250.00');
        expect(label?.textContent).toContain('Out');
        expect(label?.textContent).toContain('Planned');
    });

    it('Given_a_USD_flow_selected_When_the_currency_changes_to_ILS_and_back_to_USD_Then_its_selection_stays_cleared', async () => {
        // A checkbox filtered out of view proves nothing about its underlying selection: it
        // would read as absent from the DOM whether or not the selection itself was cleared.
        // Switching back to USD is what actually exercises whether the clearing happened.
        fakeFetch(ledger([RENT_ILS, SALARY_USD], []));
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });
        await screen.findByLabelText(/Rent/);

        await user.selectOptions(screen.getByLabelText('Currency'), 'USD');
        const salaryCheckbox = await screen.findByLabelText(/Salary/);
        await user.click(salaryCheckbox);
        expect(salaryCheckbox).toHaveProperty('checked', true);

        await user.selectOptions(screen.getByLabelText('Currency'), 'ILS');
        expect(screen.queryByLabelText(/Salary/)).toBeNull();
        expect(screen.getByLabelText(/Rent/)).toHaveProperty('checked', false);

        await user.selectOptions(screen.getByLabelText('Currency'), 'USD');
        expect(await screen.findByLabelText(/Salary/)).toHaveProperty('checked', false);
    });

    it('Given_zero_flows_selected_When_defined_Then_it_posts_an_empty_flows_array_for_the_do_nothing_baseline', async () => {
        const requests = fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return jsonResponse(BASELINE, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });
        await screen.findByText('No planned flows in ILS yet.');

        await user.type(screen.getByLabelText('Name'), 'Baseline');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        await waitFor(() => {
            expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        });
        const post = requests.find((request) => request.method === 'POST');
        expect(post?.body).toEqual({
            name: 'Baseline',
            currency: 'ILS',
            flows: [],
            growthRatePerStep: '0',
        });
    });

    it('Given_a_flow_checked_When_defined_Then_its_id_is_posted_in_flows', async () => {
        const requests = fakeFetch(
            ledger([RENT_ILS], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return jsonResponse(AGGRESSIVE, 201);
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Aggressive saving');
        await user.click(await screen.findByLabelText(/Rent/));
        await user.clear(screen.getByLabelText('Growth rate per step'));
        await user.type(screen.getByLabelText('Growth rate per step'), '0.005');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        await waitFor(() => {
            expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        });
        const post = requests.find((request) => request.method === 'POST');
        expect(post?.body).toEqual({
            name: 'Aggressive saving',
            currency: 'ILS',
            flows: [RENT_ID],
            growthRatePerStep: '0.005',
        });
    });

    it('Given_an_unknown_plan_422_When_defined_Then_the_server_detail_is_shown', async () => {
        fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 422,
                        code: 'UNKNOWN_PLAN',
                        detail: 'No planned flow has this id.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Aggressive saving');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('No planned flow has this id.')).toBeDefined();
    });

    it('Given_a_rule_violated_422_When_defined_Then_the_server_detail_is_shown', async () => {
        fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 422,
                        code: 'RULE_VIOLATED',
                        detail: 'A flow is listed twice.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Aggressive saving');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('A flow is listed twice.')).toBeDefined();
    });

    it('Given_a_400_listing_a_name_error_When_defined_Then_the_message_is_on_the_Name_field', async () => {
        fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'name', message: 'must not be blank' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        // A blank Name never reaches fetch at all: native `required` refuses to fire the form's
        // submit event. A name of only spaces has a non-empty value, so it passes that check and
        // reaches the server, which is the case this test exercises.
        await user.type(screen.getByLabelText('Name'), '   ');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        const error = await screen.findByText('must not be blank');
        expect(screen.getByLabelText('Name').getAttribute('aria-describedby')).toBe(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_400_listing_a_currency_error_When_defined_Then_the_message_is_on_the_Currency_field', async () => {
        fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'currency', message: 'must not be null' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Aggressive saving');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        const error = await screen.findByText('must not be null');
        expect(screen.getByLabelText('Currency').getAttribute('aria-describedby')).toBe(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Currency'));
        });
    });

    it('Given_a_400_listing_a_growthRatePerStep_error_When_defined_Then_the_message_is_on_its_field', async () => {
        // Reachable through the UI: the ledger's own @Pattern rejects an exponent, a percentage
        // sign or any character outside plain decimal text, none of which the free-text field
        // itself refuses.
        fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [
                            { field: 'growthRatePerStep', message: 'must be a decimal number' },
                        ],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Aggressive saving');
        await user.clear(screen.getByLabelText('Growth rate per step'));
        await user.type(screen.getByLabelText('Growth rate per step'), '0.5%');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        const error = await screen.findByText('must be a decimal number');
        // Also described by its hint ("e.g. 0.005 per step"), so the error id is one of two.
        expect(
            screen
                .getByLabelText('Growth rate per step')
                .getAttribute('aria-describedby')
                ?.split(' '),
        ).toContain(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Growth rate per step'));
        });
    });

    it('Given_a_scenario_defined_successfully_When_the_list_shows_it_Then_the_form_is_reset_and_invalidates_only_scenarios', async () => {
        let served: readonly unknown[] = [];
        const requests = fakeFetch((request) => {
            if (request.url === FLOWS_URL && request.method === 'GET') return jsonResponse([]);
            if (request.url === SCENARIOS_URL && request.method === 'GET') {
                return jsonResponse(served);
            }
            if (request.url === SCENARIOS_URL && request.method === 'POST') {
                served = [BASELINE];
                return jsonResponse(BASELINE, 201);
            }
            return unexpectedRequest(request);
        });
        const { queryClient } = renderScenarios();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });

        await user.type(screen.getByLabelText('Name'), 'Baseline');
        await user.click(screen.getByRole('button', { name: 'Define scenario' }));

        await screen.findByRole('rowheader', { name: 'Baseline' });
        expect(screen.getByLabelText('Name')).toHaveProperty('value', '');
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([planKeys.scenarios]);
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard that reads render-time state lets both through.
        const requests = fakeFetch(
            ledger([], [], (request) => {
                if (request.url === SCENARIOS_URL && request.method === 'POST') {
                    return pendingResponse();
                }
                return unexpectedRequest(request);
            }),
        );
        renderScenarios();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Define a scenario' });
        await user.type(screen.getByLabelText('Name'), 'Baseline');
        const form = screen.getByLabelText('Name').closest('form');
        if (form === null) {
            throw new Error('the Name field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        // By the time pending has rendered, every request either submit started has reached fetch.
        const submit = screen.getByRole('button', { name: 'Define scenario' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });
});
