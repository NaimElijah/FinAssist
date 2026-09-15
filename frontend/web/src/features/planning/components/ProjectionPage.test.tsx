// @vitest-environment jsdom
// Renders the planning routes through the feature's own route table, so it needs a DOM. fetch is
// faked, so the feature's real api/ module and its mapping run under test; the account choices
// src/app would provide are provided here directly, in planning's own terms.

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { Outlet, type RouteObject } from 'react-router';
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
import { focusedElementHolds } from '@/shared/testing/focus';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { AccountOptionsContext } from '../hooks/accountOptions';
import { planningRoutes } from '../index';
import type { AccountOptions } from '../types/accountOption';

const SCENARIOS_URL = '/ledger/api/v1/scenarios';

const SAVING = {
    id: 's-ils',
    name: 'Aggressive saving',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0.005',
} as const;

const ABROAD = {
    id: 's-usd',
    name: 'Move abroad',
    currency: 'USD',
    flows: [],
    growthRatePerStep: '0',
} as const;

/** Two ILS accounts and one USD account, as src/app would adapt them from accounting. */
const LOADED: AccountOptions = {
    status: 'loaded',
    options: [
        { ref: 'acc-checking', label: 'Checking', currency: 'ILS' },
        { ref: 'acc-brokerage', label: 'Brokerage', currency: 'ILS' },
        { ref: 'acc-us-checking', label: 'US checking', currency: 'USD' },
    ],
    failure: null,
};

/** A balance point on `date`, its amounts in ILS exactly as given. */
function wirePoint(date: string, cash: string, invested: string, total: string) {
    return {
        date,
        cash: { amount: cash, currency: 'ILS' },
        invested: { amount: invested, currency: 'ILS' },
        total: { amount: total, currency: 'ILS' },
    };
}

/** `SAVING` projected monthly, its cash dipping below zero at the second step. */
const WITH_SHORTFALL = {
    scenario: SAVING.id,
    horizon: { from: '2026-09-01', to: '2026-11-01' },
    step: 'MONTHLY',
    currency: 'ILS',
    points: [
        wirePoint('2026-09-01', '100.00', '500.00', '600.00'),
        wirePoint('2026-10-01', '-12500.00', '502.50', '-11997.50'),
        wirePoint('2026-11-01', '40.00', '505.01', '545.01'),
    ],
    firstShortfall: '2026-10-01',
};

/** `SAVING` projected weekly, its cash never below zero. */
const WITHOUT_SHORTFALL = {
    scenario: SAVING.id,
    horizon: { from: '2026-09-01', to: '2026-09-15' },
    step: 'WEEKLY',
    currency: 'ILS',
    points: [
        wirePoint('2026-09-01', '100.00', '0.00', '100.00'),
        wirePoint('2026-09-08', '110.00', '0.00', '110.00'),
        wirePoint('2026-09-15', '120.00', '0.00', '120.00'),
    ],
};

const CAVEAT =
    'A dip that opens and closes inside a single step is not shown. Projecting at a finer cadence narrows that window; nothing closes it.';

/** Whether `request` is a projection of any scenario. */
function isProjection(request: RecordedRequest): boolean {
    return request.url.startsWith(`${SCENARIOS_URL}/`) && request.url.includes('/projection?');
}

/**
 * A ledger holding `scenarios`, answering every projection through `projection`, or refusing it
 * as unplanned when none is given.
 */
function ledger(scenarios: readonly unknown[], projection?: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === SCENARIOS_URL) return jsonResponse(scenarios);
        if (projection !== undefined && isProjection(request)) return projection(request);
        return unexpectedRequest(request);
    };
}

/** Render the projection page, with `accountOptions` provided as src/app would provide them. */
function renderProjection(accountOptions: AccountOptions = LOADED) {
    const routes: RouteObject[] = [
        {
            element: (
                <AccountOptionsContext value={accountOptions}>
                    <Outlet />
                </AccountOptionsContext>
            ),
            children: planningRoutes,
        },
    ];
    return renderRoutes(routes, '/planning/projection');
}

/** Choose `scenario`, enter the horizon, pick `step`, and submit. */
async function project(
    user: ReturnType<typeof userEvent.setup>,
    {
        scenario = 'Aggressive saving (ILS)',
        from = '2026-09-01',
        to = '2026-11-01',
        step = 'Monthly',
    }: { scenario?: string; from?: string; to?: string; step?: string } = {},
) {
    await user.selectOptions(await screen.findByLabelText('Scenario'), scenario);
    fireEvent.change(screen.getByLabelText('From'), { target: { value: from } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: to } });
    await user.selectOptions(screen.getByLabelText('Step'), step);
    await user.click(screen.getByRole('button', { name: 'Project' }));
}

/** A paragraph whose whole text is exactly `text`, however many elements it spans. */
function paragraph(text: string) {
    return (_content: string, element: Element | null) =>
        element?.tagName === 'P' && element.textContent === text;
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('ProjectionPage', () => {
    it('Given_the_planning_nav_When_the_projection_page_renders_Then_Projection_is_marked_current', async () => {
        fakeFetch(ledger([SAVING]));

        renderProjection();

        await screen.findByRole('heading', { level: 2, name: 'Project a scenario' });
        expect(
            screen
                .getByRole('navigation', { name: 'Planning' })
                .querySelector('[aria-current="page"]')?.textContent,
        ).toBe('Projection');
    });

    it('Given_a_projection_with_a_shortfall_When_projected_Then_the_callout_names_the_step_it_ends_at_with_the_caveat', async () => {
        fakeFetch(ledger([SAVING], () => jsonResponse(WITH_SHORTFALL)));
        renderProjection();

        await project(userEvent.setup({ delay: null }));

        expect(
            await screen.findByText(
                paragraph('Cash first falls below zero at the step ending 1 Oct 2026.'),
            ),
        ).toBeDefined();
        expect(screen.getByText(CAVEAT)).toBeDefined();
    });

    it('Given_a_projection_without_a_shortfall_When_projected_weekly_Then_the_callout_says_cash_never_falls_below_zero_at_a_weekly_cadence', async () => {
        const requests = fakeFetch(ledger([SAVING], () => jsonResponse(WITHOUT_SHORTFALL)));
        renderProjection();

        await project(userEvent.setup({ delay: null }), { to: '2026-09-15', step: 'Weekly' });

        expect(
            await screen.findByText(paragraph('Cash never falls below zero at a weekly cadence.')),
        ).toBeDefined();
        expect(screen.getByText(CAVEAT)).toBeDefined();
        expect(requests.filter(isProjection).map((request) => request.url)).toEqual([
            `${SCENARIOS_URL}/${SAVING.id}/projection?from=2026-09-01&to=2026-09-15&step=WEEKLY`,
        ]);
    });

    it('Given_a_projection_When_projected_Then_the_table_lists_every_point_exactly_as_received_beneath_the_chart', async () => {
        fakeFetch(ledger([SAVING], () => jsonResponse(WITH_SHORTFALL)));
        renderProjection();

        await project(userEvent.setup({ delay: null }));

        const table = await screen.findByRole('table', { name: 'Projection' });
        expect(
            screen.getByRole('img', { name: /Projected cash and invested balances/ }),
        ).toBeDefined();
        const rows = within(table).getAllByRole('row').slice(1);
        expect(
            rows.map((row) =>
                [...row.querySelectorAll('time, data')].map(
                    (cell) => cell.getAttribute('datetime') ?? cell.getAttribute('value'),
                ),
            ),
        ).toEqual([
            ['2026-09-01', '100.00', '500.00', '600.00'],
            ['2026-10-01', '-12500.00', '502.50', '-11997.50'],
            ['2026-11-01', '40.00', '505.01', '545.01'],
        ]);
        expect(within(rows[1] ?? table).getByText('-₪12,500.00')).toBeDefined();
    });

    it('Given_a_projection_in_flight_When_projected_Then_pending_is_shown', async () => {
        fakeFetch(ledger([SAVING], () => pendingResponse()));
        renderProjection();

        await project(userEvent.setup({ delay: null }));

        expect(await screen.findByText('Projecting…')).toBeDefined();
    });

    it('Given_a_horizon_the_ledger_refuses_When_projected_Then_the_server_detail_is_shown_and_takes_focus', async () => {
        fakeFetch(
            ledger([SAVING], () =>
                problemResponse({
                    status: 422,
                    code: 'RULE_VIOLATED',
                    detail: 'The horizon is not a whole number of steps.',
                }),
            ),
        );
        renderProjection();

        await project(userEvent.setup({ delay: null }));

        const alert = await screen.findByRole('alert');
        expect(
            within(alert).getByText('The horizon is not a whole number of steps.'),
        ).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_a_horizon_the_ledger_refuses_When_projected_Then_no_retry_is_offered', async () => {
        // A 422 is the ledger's considered answer to these fields; sending them again repeats it.
        fakeFetch(
            ledger([SAVING], () =>
                problemResponse({
                    status: 422,
                    code: 'RULE_VIOLATED',
                    detail: 'The horizon is not a whole number of steps.',
                }),
            ),
        );
        renderProjection();

        await project(userEvent.setup({ delay: null }));

        const alert = await screen.findByRole('alert');
        expect(
            within(alert).getByText('The horizon is not a whole number of steps.'),
        ).toBeDefined();
        expect(within(alert).queryByRole('button', { name: 'Try again' })).toBeNull();
    });

    it('Given_a_projection_shown_When_its_fields_are_submitted_again_unchanged_Then_it_is_requested_again_and_the_new_answer_is_shown', async () => {
        let answer: unknown = WITH_SHORTFALL;
        const requests = fakeFetch(ledger([SAVING], () => jsonResponse(answer)));
        renderProjection();
        const user = userEvent.setup({ delay: null });
        await project(user);
        await screen.findByText(
            paragraph('Cash first falls below zero at the step ending 1 Oct 2026.'),
        );

        // The same fields can meet a different answer - a transaction recorded in accounting since
        // moves an opening balance - so submitting them again must ask the ledger again.
        answer = { ...WITH_SHORTFALL, firstShortfall: undefined };
        await user.click(screen.getByRole('button', { name: 'Project' }));

        expect(
            await screen.findByText(paragraph('Cash never falls below zero at a monthly cadence.')),
        ).toBeDefined();
        const urls = requests.filter(isProjection).map((request) => request.url);
        expect(urls).toHaveLength(2);
        expect(urls[1]).toBe(urls[0]);
    });

    it('Given_a_projection_that_failed_When_its_fields_are_submitted_again_unchanged_Then_it_is_requested_again_and_the_new_failure_takes_focus', async () => {
        let detail = 'The horizon is not a whole number of steps.';
        const requests = fakeFetch(
            ledger([SAVING], () => problemResponse({ status: 422, code: 'RULE_VIOLATED', detail })),
        );
        renderProjection();
        const user = userEvent.setup({ delay: null });
        await project(user);
        await screen.findByText(detail);

        detail = 'The horizon does not start before it ends.';
        await user.click(screen.getByRole('button', { name: 'Project' }));

        const alert = (await screen.findByText(detail)).closest('[role="alert"]');
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
        expect(requests.filter(isProjection)).toHaveLength(2);
    });

    it('Given_a_projection_that_failed_When_Try_again_is_clicked_Then_it_is_requested_again_and_shown', async () => {
        let failing = true;
        const requests = fakeFetch(
            ledger([SAVING], () =>
                failing
                    ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                    : jsonResponse(WITH_SHORTFALL),
            ),
        );
        renderProjection();
        const user = userEvent.setup({ delay: null });
        await project(user);
        const alert = await screen.findByRole('alert');

        failing = false;
        await user.click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByRole('table', { name: 'Projection' })).toBeDefined();
        expect(requests.filter(isProjection)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on the section it restored.
        await waitFor(() => {
            expect(document.activeElement).toBe(
                screen.getByRole('heading', { level: 2, name: 'Projected balances' }),
            );
        });
    });

    it('Given_scenarios_in_two_currencies_When_each_is_chosen_Then_only_accounts_in_its_currency_are_offered', async () => {
        fakeFetch(ledger([SAVING, ABROAD]));
        renderProjection();
        const user = userEvent.setup({ delay: null });

        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );
        const labelsIn = (group: string) =>
            within(screen.getByRole('group', { name: group }))
                .getAllByRole('checkbox')
                .map((checkbox) => checkbox.closest('label')?.textContent);
        expect(labelsIn('Cash accounts')).toEqual(['Checking', 'Brokerage']);
        expect(labelsIn('Invested accounts')).toEqual(['Checking', 'Brokerage']);
        expect(
            screen.getByText(
                'A projection covers one currency, so only accounts in ILS are offered.',
            ),
        ).toBeDefined();

        await user.selectOptions(screen.getByLabelText('Scenario'), 'Move abroad (USD)');
        expect(labelsIn('Cash accounts')).toEqual(['US checking']);
    });

    it('Given_ILS_accounts_checked_When_the_scenario_becomes_USD_and_is_projected_Then_only_USD_accounts_are_sent', async () => {
        const requests = fakeFetch(ledger([SAVING, ABROAD], () => pendingResponse()));
        renderProjection();
        const user = userEvent.setup({ delay: null });
        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );
        await user.click(
            within(screen.getByRole('group', { name: 'Cash accounts' })).getByLabelText('Checking'),
        );
        await user.click(
            within(screen.getByRole('group', { name: 'Invested accounts' })).getByLabelText(
                'Brokerage',
            ),
        );

        await user.selectOptions(screen.getByLabelText('Scenario'), 'Move abroad (USD)');
        await user.click(
            within(screen.getByRole('group', { name: 'Cash accounts' })).getByLabelText(
                'US checking',
            ),
        );
        await project(user, { scenario: 'Move abroad (USD)' });

        await waitFor(() => {
            expect(requests.filter(isProjection).map((request) => request.url)).toEqual([
                `${SCENARIOS_URL}/${ABROAD.id}/projection?from=2026-09-01&to=2026-11-01&step=MONTHLY&cash=acc-us-checking`,
            ]);
        });
    });

    it('Given_no_scenario_chosen_When_the_page_renders_Then_no_account_is_offered_and_the_hint_says_why', async () => {
        fakeFetch(ledger([SAVING]));
        renderProjection();

        expect(
            await screen.findByText('Choose a scenario to offer the accounts in its currency.'),
        ).toBeDefined();
        expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    });

    it('Given_no_accounts_in_the_scenarios_currency_When_it_is_chosen_Then_each_group_says_so', async () => {
        fakeFetch(ledger([SAVING]));
        renderProjection({
            status: 'loaded',
            options: [{ ref: 'acc-us-checking', label: 'US checking', currency: 'USD' }],
            failure: null,
        });
        const user = userEvent.setup({ delay: null });

        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );

        expect(screen.getAllByText('No accounts in ILS.')).toHaveLength(2);
    });

    it('Given_the_accounts_still_loading_When_the_page_renders_Then_the_pickers_say_so', async () => {
        fakeFetch(ledger([SAVING]));

        renderProjection({ status: 'loading' });

        expect(await screen.findByText('Loading accounts…')).toBeDefined();
    });

    it('Given_the_accounts_failed_to_load_When_the_page_renders_Then_only_the_pickers_show_the_failure_with_a_retry', async () => {
        fakeFetch(ledger([SAVING]));
        const retry = vi.fn();
        renderProjection({
            status: 'failed',
            failure: {
                error: new TypeError('network down'),
                retrying: false,
                retry,
            },
        });
        const user = userEvent.setup({ delay: null });

        const alert = await screen.findByRole('alert');
        await user.click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(retry).toHaveBeenCalledTimes(1);
        // The rest of the form still works: a projection may open with no accounts at all.
        expect(screen.getByLabelText('Scenario')).toBeDefined();
        expect(screen.getByRole('button', { name: 'Project' })).toBeDefined();
        expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    });

    it('Given_the_accounts_failed_to_load_When_projected_Then_it_is_requested_with_both_lines_opening_at_zero', async () => {
        const requests = fakeFetch(ledger([SAVING], () => jsonResponse(WITH_SHORTFALL)));
        renderProjection({
            status: 'failed',
            failure: { error: new Error('network down'), retrying: false, retry: vi.fn() },
        });

        await project(userEvent.setup({ delay: null }));

        await screen.findByRole('table', { name: 'Projection' });
        expect(requests.filter(isProjection).map((request) => request.url)).toEqual([
            `${SCENARIOS_URL}/${SAVING.id}/projection?from=2026-09-01&to=2026-11-01&step=MONTHLY`,
        ]);
    });

    it('Given_accounts_on_screen_When_a_later_refetch_fails_Then_the_checkboxes_stay_beside_the_failure', async () => {
        fakeFetch(ledger([SAVING]));
        renderProjection({
            ...LOADED,
            failure: { error: new TypeError('network down'), retrying: true, retry: vi.fn() },
        });
        const user = userEvent.setup({ delay: null });

        await user.selectOptions(
            await screen.findByLabelText('Scenario'),
            'Aggressive saving (ILS)',
        );

        const alert = screen.getByRole('alert');
        expect(within(alert).getByRole('button', { name: 'Try again' })).toHaveProperty(
            'disabled',
            true,
        );
        expect(
            within(screen.getByRole('group', { name: 'Cash accounts' })).getAllByRole('checkbox'),
        ).toHaveLength(2);
    });

    it('Given_no_scenarios_When_the_page_renders_Then_the_empty_state_says_to_define_one_first', async () => {
        fakeFetch(ledger([]));

        renderProjection();

        expect(
            await screen.findByText('No scenarios to project yet — define one under Scenarios.'),
        ).toBeDefined();
        expect(screen.queryByRole('button', { name: 'Project' })).toBeNull();
    });

    it('Given_the_scenarios_read_fails_When_the_page_renders_Then_a_retry_is_offered_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url !== SCENARIOS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                : jsonResponse([SAVING]);
        });
        renderProjection();

        const alert = await screen.findByRole('alert');
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByLabelText('Scenario')).toBeDefined();
        expect(requests).toHaveLength(2);
        // The retry button went with the failure, so focus lands on the form the retry restored.
        await waitFor(() => {
            expect(focusedElementHolds(screen.getByLabelText('Scenario'))).toBe(true);
        });
    });
});
