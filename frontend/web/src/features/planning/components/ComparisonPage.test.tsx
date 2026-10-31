// @vitest-environment jsdom
// Renders the planning routes through the feature's own route table, so it needs a DOM. fetch is
// faked, so the feature's real api/ module and its mapping run under test; the account choices
// src/app would provide are provided here directly, in planning's own terms.

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { Outlet, type RouteObject } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import type { WriteAccess } from '@/shared/lib/writeAccess';
import { accessibleDescriptionOf } from '@/shared/testing/descriptions';
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
import { statusMessages } from '@/shared/testing/status';

import { AccountOptionsContext } from '../hooks/accountOptions';
import { planningRoutes } from '../index';
import type { AccountOptions } from '../types/accountOption';

const SCENARIOS_URL = '/ledger/api/v1/scenarios';
const COMPARISONS_URL = '/ledger/api/v1/scenario-comparisons';

const DO_NOTHING = {
    id: 's-base',
    name: 'Do nothing',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0',
} as const;

const SAVING = {
    id: 's-save',
    name: 'Aggressive saving',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0.005',
} as const;

const CAUTIOUS = {
    id: 's-cautious',
    name: 'Cautious',
    currency: 'ILS',
    flows: [],
    growthRatePerStep: '0.001',
} as const;

const ABROAD = {
    id: 's-abroad',
    name: 'Move abroad',
    currency: 'USD',
    flows: [],
    growthRatePerStep: '0',
} as const;

/** Every scenario, in the order the ledger lists them. */
const SCENARIOS = [DO_NOTHING, SAVING, CAUTIOUS, ABROAD];

/** One ILS account and one USD account, as src/app would adapt them from accounting. */
const LOADED: AccountOptions = {
    status: 'loaded',
    options: [
        { ref: 'acc-checking', label: 'Checking', currency: 'ILS' },
        { ref: 'acc-us-checking', label: 'US checking', currency: 'USD' },
    ],
    failure: null,
};

/** A step's amounts on `date`, in ILS exactly as given. */
function wirePoint(date: string, cash: string, invested: string, total: string) {
    return {
        date,
        cash: { amount: cash, currency: 'ILS' },
        invested: { amount: invested, currency: 'ILS' },
        total: { amount: total, currency: 'ILS' },
    };
}

/** `DO_NOTHING` as a comparison lists it: the baseline, so no divergence of its own. */
const DO_NOTHING_COMPARED = {
    id: DO_NOTHING.id,
    name: DO_NOTHING.name,
    points: [wirePoint('2026-11-01', '100.00', '0.00', '100.00')],
    endState: wirePoint('2026-11-01', '100.00', '0.00', '100.00'),
    divergence: [],
};

/** `SAVING` as a comparison against `DO_NOTHING` lists it, its divergence signed at every step. */
const SAVING_COMPARED = {
    id: SAVING.id,
    name: SAVING.name,
    points: [wirePoint('2026-11-01', '-50.00', '300.00', '250.00')],
    endState: wirePoint('2026-11-01', '-50.00', '300.00', '250.00'),
    divergence: [
        wirePoint('2026-09-01', '0.00', '0.00', '0.00'),
        wirePoint('2026-10-01', '-75.50', '100.00', '24.50'),
        wirePoint('2026-11-01', '-150.00', '300.00', '150.00'),
    ],
};

/** `SAVING` compared against `DO_NOTHING`. */
const COMPARISON = {
    baseline: DO_NOTHING.id,
    currency: 'ILS',
    horizon: { from: '2026-09-01', to: '2026-11-01' },
    step: 'MONTHLY',
    scenarios: [DO_NOTHING_COMPARED, SAVING_COMPARED],
};

/** The most scenarios the ledger compares at once; one more is refused with 400. */
const MAXIMUM_SCENARIOS = 10;

/** `count` scenarios in one currency, named `Scenario 1` onward. */
function manyScenarios(count: number) {
    return Array.from({ length: count }, (_, index) => ({
        id: `s-${String(index + 1)}`,
        name: `Scenario ${String(index + 1)}`,
        currency: 'ILS',
        flows: [],
        growthRatePerStep: '0',
    }));
}

/** The checkbox labels of `scenarios`, as the Scenarios group shows them. */
function labelsOf(scenarios: readonly { name: string; currency: string }[]): string[] {
    return scenarios.map((scenario) => `${scenario.name} (${scenario.currency})`);
}

function isComparison(request: RecordedRequest): boolean {
    return request.url.startsWith(`${COMPARISONS_URL}?`);
}

/**
 * A ledger holding `scenarios`, answering every comparison through `comparison`, or refusing it
 * as unplanned when none is given.
 */
function ledger(scenarios: readonly unknown[], comparison?: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === SCENARIOS_URL) return jsonResponse(scenarios);
        if (comparison !== undefined && isComparison(request)) return comparison(request);
        return unexpectedRequest(request);
    };
}

/** Render the comparison page, with `accountOptions` provided as src/app would provide them. */
function renderComparison(
    accountOptions: AccountOptions = LOADED,
    access: WriteAccess = { canWrite: true },
) {
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
    return renderRoutes(routes, '/planning/compare', { access });
}

/** The Scenarios checkbox labelled `label`. */
function scenarioCheckbox(label: string): HTMLElement {
    return within(screen.getByRole('group', { name: 'Scenarios' })).getByLabelText(label);
}

/** Every checkbox in the Scenarios group, in the order offered. */
function scenarioCheckboxes(): HTMLElement[] {
    return within(screen.getByRole('group', { name: 'Scenarios' })).getAllByRole('checkbox');
}

/** The words of every option the Baseline select offers. */
function baselineOptions(): (string | null)[] {
    const select = screen.getByLabelText('Baseline');
    if (!(select instanceof HTMLSelectElement)) {
        throw new Error('the Baseline field is not a select');
    }
    return [...select.options].map((option) => option.textContent);
}

/** Check each of `labels`, enter the horizon, and submit. */
async function compare(user: ReturnType<typeof userEvent.setup>, labels: readonly string[]) {
    await screen.findByRole('group', { name: 'Scenarios' });
    for (const label of labels) {
        await user.click(scenarioCheckbox(label));
    }
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-01' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-01' } });
    await user.click(screen.getByRole('button', { name: 'Compare' }));
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('ComparisonPage', () => {
    it('Given_the_planning_nav_When_the_comparison_page_renders_Then_Compare_is_marked_current', async () => {
        fakeFetch(ledger(SCENARIOS));

        renderComparison();

        await screen.findByRole('heading', { level: 2, name: 'Compare scenarios' });
        expect(
            screen
                .getByRole('navigation', { name: 'Planning' })
                .querySelector('[aria-current="page"]')?.textContent,
        ).toBe('Compare');
    });

    it('Given_a_read_only_session_When_two_scenarios_are_compared_Then_the_form_is_offered_and_the_comparison_requested', async () => {
        // A projection, a comparison and a lookup are reads: a viewer sees every area (ADR-032),
        // so hiding one behind useWriteAccess would take away what the role exists for.
        const requests = fakeFetch(ledger(SCENARIOS, () => pendingResponse()));
        renderComparison(LOADED, { canWrite: false });

        await compare(userEvent.setup({ delay: null }), [
            'Aggressive saving (ILS)',
            'Do nothing (ILS)',
        ]);

        await waitFor(() => {
            expect(requests.filter(isComparison)).toHaveLength(1);
        });
    });

    it('Given_two_scenarios_and_an_account_chosen_When_compared_Then_it_requests_them_in_list_order_with_the_first_as_baseline', async () => {
        const requests = fakeFetch(ledger(SCENARIOS, () => pendingResponse()));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('group', { name: 'Scenarios' });
        await user.click(scenarioCheckbox('Aggressive saving (ILS)'));
        await user.click(scenarioCheckbox('Do nothing (ILS)'));
        await user.click(
            within(screen.getByRole('group', { name: 'Cash accounts' })).getByLabelText('Checking'),
        );

        await compare(user, []);

        await waitFor(() => {
            expect(requests.filter(isComparison).map((request) => request.url)).toEqual([
                `${COMPARISONS_URL}?scenario=s-base&scenario=s-save&baseline=s-base` +
                    '&from=2026-09-01&to=2026-11-01&step=MONTHLY&cash=acc-checking',
            ]);
        });
    });

    it('Given_a_baseline_chosen_When_compared_Then_that_baseline_is_requested', async () => {
        const requests = fakeFetch(ledger(SCENARIOS, () => pendingResponse()));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('group', { name: 'Scenarios' });
        await user.click(scenarioCheckbox('Do nothing (ILS)'));
        await user.click(scenarioCheckbox('Aggressive saving (ILS)'));

        await user.selectOptions(screen.getByLabelText('Baseline'), 'Aggressive saving');
        await compare(user, []);

        await waitFor(() => {
            expect(requests.filter(isComparison).map((request) => request.url)).toEqual([
                `${COMPARISONS_URL}?scenario=s-base&scenario=s-save&baseline=s-save` +
                    '&from=2026-09-01&to=2026-11-01&step=MONTHLY',
            ]);
        });
    });

    it('Given_one_scenario_chosen_When_compared_Then_every_checkbox_is_described_by_an_error_asking_for_two_one_takes_focus_and_nothing_is_sent', async () => {
        const requests = fakeFetch(ledger(SCENARIOS, () => jsonResponse(COMPARISON)));
        renderComparison();
        const user = userEvent.setup({ delay: null });

        await compare(user, ['Do nothing (ILS)']);

        await screen.findByText('Choose at least two scenarios to compare.');
        // Focus lands on a checkbox, not the group, so each box carries the error itself.
        for (const checkbox of scenarioCheckboxes()) {
            expect(accessibleDescriptionOf(checkbox)).toBe(
                'Choose at least two scenarios to compare.',
            );
        }
        expect(document.activeElement).toBe(scenarioCheckbox('Do nothing (ILS)'));
        expect(requests.filter(isComparison)).toHaveLength(0);
    });

    it('Given_more_scenarios_chosen_than_the_ledger_compares_When_compared_Then_every_checkbox_is_described_by_an_error_asking_for_at_most_ten_one_takes_focus_and_nothing_is_sent', async () => {
        const scenarios = manyScenarios(MAXIMUM_SCENARIOS + 1);
        const requests = fakeFetch(ledger(scenarios, () => jsonResponse(COMPARISON)));
        renderComparison();
        const user = userEvent.setup({ delay: null });

        await compare(user, labelsOf(scenarios));

        await screen.findByText('Choose at most ten scenarios to compare.');
        // Focus lands on a checkbox, not the group, so each box carries the error itself.
        for (const checkbox of scenarioCheckboxes()) {
            expect(accessibleDescriptionOf(checkbox)).toBe(
                'Choose at most ten scenarios to compare.',
            );
        }
        expect(document.activeElement).toBe(scenarioCheckbox('Scenario 1 (ILS)'));
        expect(requests.filter(isComparison)).toHaveLength(0);
    });

    it('Given_exactly_ten_scenarios_chosen_When_compared_Then_the_request_is_sent_with_every_one', async () => {
        const scenarios = manyScenarios(MAXIMUM_SCENARIOS);
        const requests = fakeFetch(ledger(scenarios, () => pendingResponse()));
        renderComparison();

        await compare(userEvent.setup({ delay: null }), labelsOf(scenarios));

        await waitFor(() => {
            expect(requests.filter(isComparison)).toHaveLength(1);
        });
        const [comparison] = requests.filter(isComparison);
        expect(
            new URL(comparison?.url ?? '', 'http://localhost').searchParams.getAll('scenario'),
        ).toHaveLength(MAXIMUM_SCENARIOS);
        expect(screen.queryByText('Choose at most ten scenarios to compare.')).toBeNull();
    });

    it('Given_a_field_error_shown_When_a_second_scenario_is_chosen_and_compared_Then_the_error_clears_and_the_request_is_sent', async () => {
        const requests = fakeFetch(ledger(SCENARIOS, () => jsonResponse(COMPARISON)));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)']);
        await screen.findByText('Choose at least two scenarios to compare.');

        await user.click(scenarioCheckbox('Aggressive saving (ILS)'));
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        await screen.findByRole('table', { name: 'End states' });
        expect(screen.queryByText('Choose at least two scenarios to compare.')).toBeNull();
        expect(requests.filter(isComparison)).toHaveLength(1);
    });

    it('Given_scenarios_checked_and_unchecked_When_the_baseline_is_offered_Then_its_options_follow_them_and_an_unchecked_baseline_is_never_requested', async () => {
        const requests = fakeFetch(ledger(SCENARIOS, () => pendingResponse()));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('group', { name: 'Scenarios' });

        expect(baselineOptions()).toEqual(['Choose scenarios first']);
        expect(screen.getByLabelText('Baseline')).toHaveProperty('disabled', true);

        await user.click(scenarioCheckbox('Do nothing (ILS)'));
        await user.click(scenarioCheckbox('Aggressive saving (ILS)'));
        await user.click(scenarioCheckbox('Cautious (ILS)'));
        expect(baselineOptions()).toEqual(['Do nothing', 'Aggressive saving', 'Cautious']);
        await user.selectOptions(screen.getByLabelText('Baseline'), 'Cautious');

        await user.click(scenarioCheckbox('Cautious (ILS)'));
        expect(baselineOptions()).toEqual(['Do nothing', 'Aggressive saving']);
        // React shows the first option whenever the value matches none, so the select alone cannot
        // tell a baseline that fell back from one left stale; the request it sends can.
        await compare(user, []);

        await waitFor(() => {
            expect(requests.filter(isComparison).map((request) => request.url)).toEqual([
                `${COMPARISONS_URL}?scenario=s-base&scenario=s-save&baseline=s-base` +
                    '&from=2026-09-01&to=2026-11-01&step=MONTHLY',
            ]);
        });
    });

    it('Given_a_comparison_When_compared_Then_the_end_state_table_marks_the_baseline_row_only', async () => {
        fakeFetch(ledger(SCENARIOS, () => jsonResponse(COMPARISON)));
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        const table = await screen.findByRole('table', { name: 'End states' });
        expect(
            within(table)
                .getAllByRole('rowheader')
                .map((cell) => cell.textContent),
        ).toEqual(['Do nothing (baseline)', 'Aggressive saving']);
        const savingRow = within(table).getByRole('rowheader', {
            name: 'Aggressive saving',
        }).parentElement;
        expect([...(savingRow?.querySelectorAll('data') ?? [])].map((data) => data.value)).toEqual([
            '-50.00',
            '300.00',
            '250.00',
        ]);
    });

    it('Given_a_comparison_When_compared_Then_each_non_baseline_scenario_has_a_divergence_table_with_the_exact_signed_strings', async () => {
        fakeFetch(ledger(SCENARIOS, () => jsonResponse(COMPARISON)));
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        const table = await screen.findByRole('table', {
            name: 'Aggressive saving versus Do nothing',
        });
        expect(screen.queryByRole('table', { name: /^Do nothing versus/ })).toBeNull();
        const rows = within(table).getAllByRole('row').slice(1);
        expect(
            rows.map((row) =>
                [...row.querySelectorAll('time, data')].map(
                    (cell) => cell.getAttribute('datetime') ?? cell.getAttribute('value'),
                ),
            ),
        ).toEqual([
            ['2026-09-01', '0.00', '0.00', '0.00'],
            ['2026-10-01', '-75.50', '100.00', '24.50'],
            ['2026-11-01', '-150.00', '300.00', '150.00'],
        ]);
        expect(within(table).getByText('-₪150.00')).toBeDefined();
    });

    it('Given_a_comparison_in_flight_When_compared_Then_a_status_announces_it_is_pending', async () => {
        fakeFetch(ledger(SCENARIOS, () => pendingResponse()));
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        await waitFor(() => {
            expect(statusMessages()).toContain('Comparing…');
        });
    });

    it('Given_an_unknown_scenario_422_When_compared_Then_the_server_detail_is_shown_and_takes_focus', async () => {
        fakeFetch(
            ledger(SCENARIOS, () =>
                problemResponse({
                    status: 422,
                    code: 'UNKNOWN_SCENARIO',
                    detail: 'No scenario has the id s-save.',
                }),
            ),
        );
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('No scenario has the id s-save.')).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_an_unknown_scenario_422_When_compared_Then_no_retry_is_offered', async () => {
        // A 422 is the ledger's considered answer to these scenarios; asking again repeats it.
        fakeFetch(
            ledger(SCENARIOS, () =>
                problemResponse({
                    status: 422,
                    code: 'UNKNOWN_SCENARIO',
                    detail: 'No scenario has the id s-save.',
                }),
            ),
        );
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('No scenario has the id s-save.')).toBeDefined();
        expect(within(alert).queryByRole('button', { name: 'Try again' })).toBeNull();
    });

    it('Given_a_comparison_shown_When_its_fields_are_submitted_again_unchanged_Then_it_is_requested_again_and_the_new_answer_is_shown', async () => {
        let answer: unknown = COMPARISON;
        const requests = fakeFetch(ledger(SCENARIOS, () => jsonResponse(answer)));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)', 'Aggressive saving (ILS)']);
        const endStates = await screen.findByRole('table', { name: 'End states' });
        expect(within(endStates).getByText('-₪50.00')).toBeDefined();

        // The same fields can meet a different answer - a transaction recorded in accounting since
        // moves an opening balance - so submitting them again must ask the ledger again.
        answer = {
            ...COMPARISON,
            scenarios: [
                DO_NOTHING_COMPARED,
                {
                    ...SAVING_COMPARED,
                    endState: wirePoint('2026-11-01', '-60.00', '300.00', '240.00'),
                },
            ],
        };
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        expect(await screen.findByText('-₪60.00')).toBeDefined();
        const urls = requests.filter(isComparison).map((request) => request.url);
        expect(urls).toHaveLength(2);
        expect(urls[1]).toBe(urls[0]);
    });

    it('Given_two_scenarios_When_compared_Then_the_result_heading_takes_focus_once_the_comparison_lands', async () => {
        // Compare keeps focus and the answer mounts below the form, so without this a screen
        // reader user hears nothing of what came back.
        fakeFetch(ledger(SCENARIOS, () => jsonResponse(COMPARISON)));
        renderComparison();

        await compare(userEvent.setup({ delay: null }), [
            'Do nothing (ILS)',
            'Aggressive saving (ILS)',
        ]);

        await screen.findByRole('table', { name: 'End states' });
        await waitFor(() => {
            expect(document.activeElement).toBe(
                screen.getByRole('heading', { level: 2, name: 'Comparison' }),
            );
        });
    });

    it('Given_a_comparison_shown_When_its_fields_are_submitted_again_unchanged_Then_the_result_heading_takes_focus_once_the_new_answer_lands', async () => {
        // The answer refreshes in place, so nothing new mounts to announce it. The second answer
        // is held, to show focus waits for it rather than following the answer already on screen.
        let release: (response: Response) => void = () => undefined;
        let held = false;
        fakeFetch(
            ledger(SCENARIOS, () =>
                held
                    ? new Promise<Response>((resolve) => {
                          release = resolve;
                      })
                    : jsonResponse(COMPARISON),
            ),
        );
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)', 'Aggressive saving (ILS)']);
        await screen.findByRole('table', { name: 'End states' });

        held = true;
        const compareButton = screen.getByRole('button', { name: 'Compare' });
        await user.click(compareButton);
        expect(document.activeElement).toBe(compareButton);

        act(() => {
            release(
                jsonResponse({
                    ...COMPARISON,
                    scenarios: [
                        DO_NOTHING_COMPARED,
                        {
                            ...SAVING_COMPARED,
                            endState: wirePoint('2026-11-01', '-60.00', '300.00', '240.00'),
                        },
                    ],
                }),
            );
        });
        await screen.findByText('-₪60.00');
        await waitFor(() => {
            expect(document.activeElement).toBe(
                screen.getByRole('heading', { level: 2, name: 'Comparison' }),
            );
        });
    });

    it('Given_a_comparison_shown_When_it_is_requested_again_and_fails_Then_the_failure_shows_above_the_comparison_it_kept', async () => {
        // A result stays below a later failure: the owner keeps reading what the ledger last
        // answered, and the failure says the new request did not land.
        let failing = false;
        fakeFetch(
            ledger(SCENARIOS, () =>
                failing
                    ? problemResponse({ status: 503, code: 'UNAVAILABLE', detail: 'Try later.' })
                    : jsonResponse(COMPARISON),
            ),
        );
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)', 'Aggressive saving (ILS)']);
        await screen.findByRole('table', { name: 'End states' });

        failing = true;
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Try later.')).toBeDefined();
        expect(screen.getByRole('table', { name: 'End states' })).toBeDefined();
    });

    it('Given_a_comparison_that_failed_When_its_fields_are_submitted_again_unchanged_Then_it_is_requested_again_and_the_new_failure_takes_focus', async () => {
        let detail = 'No scenario has the id s-save.';
        const requests = fakeFetch(
            ledger(SCENARIOS, () =>
                problemResponse({ status: 422, code: 'UNKNOWN_SCENARIO', detail }),
            ),
        );
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)', 'Aggressive saving (ILS)']);
        await screen.findByText(detail);

        detail = 'No scenario has the id s-base.';
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        const alert = (await screen.findByText(detail)).closest('[role="alert"]');
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
        expect(requests.filter(isComparison)).toHaveLength(2);
    });

    it('Given_a_comparison_that_failed_When_Try_again_is_clicked_Then_it_is_requested_again_and_shown', async () => {
        let failing = true;
        const requests = fakeFetch(
            ledger(SCENARIOS, () =>
                failing
                    ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                    : jsonResponse(COMPARISON),
            ),
        );
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await compare(user, ['Do nothing (ILS)', 'Aggressive saving (ILS)']);
        const alert = await screen.findByRole('alert');

        failing = false;
        await user.click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByRole('table', { name: 'End states' })).toBeDefined();
        expect(requests.filter(isComparison)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on the section it restored.
        await waitFor(() => {
            expect(document.activeElement).toBe(
                screen.getByRole('heading', { level: 2, name: 'Comparison' }),
            );
        });
    });

    it('Given_no_scenario_chosen_When_the_page_renders_Then_no_account_is_offered_and_the_hint_says_why', async () => {
        fakeFetch(ledger(SCENARIOS));
        renderComparison();

        expect(
            await screen.findByText('Choose scenarios to offer the accounts in their currency.'),
        ).toBeDefined();
        expect(screen.queryByRole('group', { name: 'Cash accounts' })).toBeNull();
    });

    it('Given_scenarios_in_two_currencies_chosen_When_the_accounts_are_offered_Then_none_is_and_the_hint_says_a_comparison_covers_one', async () => {
        fakeFetch(ledger(SCENARIOS));
        renderComparison();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('group', { name: 'Scenarios' });

        await user.click(scenarioCheckbox('Do nothing (ILS)'));
        expect(
            within(screen.getByRole('group', { name: 'Cash accounts' })).getAllByRole('checkbox'),
        ).toHaveLength(1);
        expect(
            screen.getByText(
                'A comparison covers one currency, so only accounts in ILS are offered.',
            ),
        ).toBeDefined();
        await user.click(scenarioCheckbox('Move abroad (USD)'));

        expect(
            screen.getByText(
                'The chosen scenarios are in more than one currency, and a comparison covers one.',
            ),
        ).toBeDefined();
        expect(screen.queryByRole('group', { name: 'Cash accounts' })).toBeNull();
    });

    it('Given_no_scenarios_When_the_page_renders_Then_the_empty_state_says_to_define_some_first', async () => {
        fakeFetch(ledger([]));

        renderComparison();

        expect(
            await screen.findByText(
                'No scenarios to compare yet — define at least two under Scenarios.',
            ),
        ).toBeDefined();
        expect(screen.queryByRole('button', { name: 'Compare' })).toBeNull();
    });

    it('Given_the_scenarios_read_fails_When_the_page_renders_Then_a_retry_is_offered_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url !== SCENARIOS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                : jsonResponse(SCENARIOS);
        });
        renderComparison();

        const alert = await screen.findByRole('alert');
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByRole('group', { name: 'Scenarios' })).toBeDefined();
        expect(requests).toHaveLength(2);
        // The retry button went with the failure, so focus lands on the form the retry restored.
        await waitFor(() => {
            expect(focusedElementHolds(screen.getByRole('group', { name: 'Scenarios' }))).toBe(
                true,
            );
        });
    });
});
