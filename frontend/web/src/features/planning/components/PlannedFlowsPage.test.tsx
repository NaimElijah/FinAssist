// @vitest-environment jsdom
// Renders the planning routes through the feature's own route table, so it needs a DOM. fetch is
// faked, so the feature's real api/ module and its mapping run under test.

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { type UserEvent, userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import { accessibleDescriptionOf } from '@/shared/testing/descriptions';
import {
    fakeFetch,
    type FetchAnswer,
    jsonResponse,
    networkFailure,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { changeValue, type FieldEdit } from '@/shared/testing/fieldEdits';
import { focusedElementHolds } from '@/shared/testing/focus';
import { pinClockJustAfterLocalMidnight } from '@/shared/testing/localClock';
import { renderRoutes } from '@/shared/testing/renderRoutes';
import { nameStartingWith } from '@/shared/testing/names';

import { planKeys } from '../hooks/planQueries';
import { planningRoutes } from '../index';

const FLOWS_URL = '/ledger/api/v1/planned-flows';
const SCENARIOS_URL = '/ledger/api/v1/scenarios';

const RENT_ID = 'b1b1b1b1-4d8c-4f3a-9a61-2f1c5e0d9b11';
const SALARY_ID = 'a1a1a1a1-4d8c-4f3a-9a61-2f1c5e0d9b11';

const RENT = {
    id: RENT_ID,
    description: 'Rent',
    amount: { amount: '250.00', currency: 'ILS' },
    direction: 'OUTFLOW',
    startsOn: '2026-09-01',
    recurrence: { kind: 'ONCE' },
    state: { status: 'PLANNED' },
} as const;

const SALARY = {
    id: SALARY_ID,
    description: 'Salary',
    amount: { amount: '1000.00', currency: 'ILS' },
    direction: 'INFLOW',
    startsOn: '2026-09-01',
    recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
    state: { status: 'COMMITTED', on: '2026-08-15' },
} as const;

/** The table row a flow's description heads. */
function rowOf(description: string): HTMLElement {
    const row = screen.getByRole('rowheader', { name: description }).closest('tr');
    if (row === null) {
        throw new Error(`no table row is headed by ${description}`);
    }
    return row;
}

/**
 * A ledger holding no scenarios and `flows` for the planned-flows list, both fixed; `overrides`,
 * when given, answers anything else, such as a create or a lifecycle POST.
 */
function ledger(flows: readonly unknown[], overrides?: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === SCENARIOS_URL && request.method === 'GET') return jsonResponse([]);
        if (request.url === FLOWS_URL && request.method === 'GET') return jsonResponse(flows);
        if (overrides !== undefined) return overrides(request);
        return unexpectedRequest(request);
    };
}

/**
 * A ledger holding no scenarios, and whichever flows `getFlows` returns at the time of each read;
 * `extra` answers everything else, such as a lifecycle POST. For a test whose mutation succeeds
 * and so triggers a refetch of the flows list, which must then reflect the new state.
 */
function lifecycleLedger(getFlows: () => readonly unknown[], extra: FetchAnswer): FetchAnswer {
    return (request) => {
        if (request.url === SCENARIOS_URL && request.method === 'GET') return jsonResponse([]);
        if (request.url === FLOWS_URL && request.method === 'GET') return jsonResponse(getFlows());
        return extra(request);
    };
}

/** Makes the plan-flow form's flow repeat three times, showing Cadence, Every and Times. */
async function repeatThreeTimes(user: UserEvent): Promise<void> {
    await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
    await user.type(screen.getByLabelText('Times'), '3');
}

function renderFlows() {
    return renderRoutes(planningRoutes, '/planning/flows');
}

/** A projection cached before a lifecycle action, to observe whether the action invalidates it. */
const PROJECTION_KEY = planKeys.projection({
    scenarioId: 's1',
    from: '2026-09-01',
    to: '2026-12-01',
    step: 'MONTHLY',
    cash: [],
    invested: [],
});

/** A comparison cached before a lifecycle action, likewise. */
const COMPARISON_KEY = planKeys.comparison({
    scenarioIds: ['s1', 's2'],
    baselineId: 's1',
    from: '2026-09-01',
    to: '2026-12-01',
    step: 'MONTHLY',
    cash: [],
    invested: [],
});

/**
 * The local date a lifecycle panel's Date field defaults to, written out rather than computed by
 * the code under test, so a call site reading the date some other way cannot agree with itself.
 */
const TODAY = '2026-09-15';

let restoreSink: () => void = () => undefined;
let restoreClock: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
    // Pins the clock just after local midnight in a zone where UTC is still on the day before,
    // so a default read through toISOString() would name 2026-09-14 and fail.
    restoreClock = pinClockJustAfterLocalMidnight(TODAY);
});

afterEach(() => {
    restoreSink();
    restoreClock();
});

describe('PlannedFlowsPage', () => {
    it('Given_flows_served_in_an_order_When_the_page_renders_Then_rows_keep_that_order_with_positive_amounts_and_direction_labels', async () => {
        fakeFetch(ledger([RENT, SALARY]));

        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
            'Rent',
            'Salary',
        ]);
        const rent = rowOf('Rent');
        expect(within(rent).getByText('₪250.00')).toBeDefined();
        expect(within(rent).queryByText('-₪250.00')).toBeNull();
        expect(within(rent).getByText('Out')).toBeDefined();
        const salary = rowOf('Salary');
        expect(within(salary).getByText('₪1,000.00')).toBeDefined();
        expect(within(salary).getByText('In')).toBeDefined();
    });

    it('Given_every_recurrence_kind_When_rendered_Then_each_row_shows_its_human_summary', async () => {
        const untilFlow = {
            ...RENT,
            id: 'c1c1c1c1-4d8c-4f3a-9a61-2f1c5e0d9b11',
            description: 'Subscription',
            recurrence: { kind: 'UNTIL', cadence: 'WEEKLY', interval: 2, until: '2027-01-01' },
        };
        fakeFetch(ledger([RENT, SALARY, untilFlow]));

        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        expect(within(rowOf('Rent')).getByText('Once')).toBeDefined();
        expect(within(rowOf('Salary')).getByText('Every month, 12 times')).toBeDefined();
        expect(
            within(rowOf('Subscription')).getByText('Every 2 weeks until 1 January 2027'),
        ).toBeDefined();
    });

    it('Given_a_cancelled_flow_When_rendered_Then_its_badge_and_reason_are_shown_on_its_row_only', async () => {
        const cancelled = {
            ...RENT,
            state: { status: 'CANCELLED', on: '2026-09-04', reason: 'No longer needed' },
        };
        fakeFetch(ledger([cancelled, SALARY]));

        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        expect(within(rowOf('Rent')).getByText('Cancelled')).toBeDefined();
        expect(within(rowOf('Rent')).getByText('No longer needed')).toBeDefined();
        expect(within(rowOf('Salary')).queryByText('No longer needed')).toBeNull();
    });

    it('Given_no_planned_flows_When_the_page_renders_Then_the_empty_state_is_shown', async () => {
        fakeFetch(ledger([]));

        renderFlows();

        expect(await screen.findByText('No planned flows yet')).toBeDefined();
        expect(screen.queryByRole('table')).toBeNull();
    });

    it('Given_the_list_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url === SCENARIOS_URL && request.method === 'GET') return jsonResponse([]);
            if (request.url !== FLOWS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({ status: 502, code: 'UNAVAILABLE', detail: 'Try later.' })
                : jsonResponse([]);
        });

        renderFlows();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Try later.')).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No planned flows yet')).toBeDefined();
        expect(requests.filter((request) => request.url === FLOWS_URL)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on what the retry restored.
        await waitFor(() => {
            expect(focusedElementHolds(screen.getByText('No planned flows yet'))).toBe(true);
        });
    });

    it('Given_the_network_fails_When_the_list_is_read_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        // A request that never reached the server is worth repeating, unlike a 4xx answer, so the
        // failure offers "Try again" (isRetryable).
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url === SCENARIOS_URL && request.method === 'GET') return jsonResponse([]);
            if (request.url !== FLOWS_URL) return unexpectedRequest(request);
            return failing ? networkFailure() : jsonResponse([]);
        });

        renderFlows();

        const alert = await screen.findByRole('alert');
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No planned flows yet')).toBeDefined();
        expect(requests.filter((request) => request.url === FLOWS_URL)).toHaveLength(2);
    });
});

describe('PlannedFlowsPage list heading', () => {
    it('Given_planned_flows_When_rendered_Then_their_table_sits_under_its_own_heading', async () => {
        fakeFetch(ledger([RENT]));
        renderFlows();

        const list = await screen.findByRole('region', { name: 'All planned flows' });
        expect(
            within(list).getByRole('heading', { level: 2, name: 'All planned flows' }),
        ).toBeDefined();
        expect(await within(list).findByRole('table', { name: 'Planned flows' })).toBeDefined();
    });
});

describe('PlannedFlowsPage lifecycle actions', () => {
    it('Given_a_planned_flow_When_rendered_Then_its_row_offers_Commit_and_Cancel_but_not_Realise', async () => {
        fakeFetch(ledger([RENT]));
        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        const row = rowOf('Rent');

        expect(within(row).getByRole('button', { name: nameStartingWith('Commit') })).toBeDefined();
        expect(within(row).getByRole('button', { name: nameStartingWith('Cancel') })).toBeDefined();
        expect(within(row).queryByRole('button', { name: nameStartingWith('Realise') })).toBeNull();
    });

    it('Given_a_planned_flow_When_rendered_Then_each_action_is_named_by_its_word_then_the_row', async () => {
        // Out of the table, every row's "Commit" sounds alike; the flow's description and start
        // tell them apart, after the visible word so a voice command naming it still matches.
        fakeFetch(ledger([RENT]));
        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        const row = rowOf('Rent');

        expect(
            within(row).getByRole('button', { name: 'Commit Rent, starting 1 Sept 2026' }),
        ).toBeDefined();
        expect(
            within(row).getByRole('button', { name: 'Cancel Rent, starting 1 Sept 2026' }),
        ).toBeDefined();
    });

    it('Given_a_planned_flow_When_Cancel_is_opened_Then_the_panel_title_names_the_row', async () => {
        fakeFetch(ledger([RENT]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', {
                name: 'Cancel Rent, starting 1 Sept 2026',
            }),
        );

        expect(
            screen.getByRole('region', {
                name: 'Cancel planned flow: Rent, starting 1 Sept 2026',
            }),
        ).toBeDefined();
    });

    it('Given_a_committed_flow_When_rendered_Then_its_row_offers_Realise_and_Cancel_but_not_Commit', async () => {
        fakeFetch(ledger([SALARY]));
        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });
        const row = rowOf('Salary');

        expect(
            within(row).getByRole('button', { name: nameStartingWith('Realise') }),
        ).toBeDefined();
        expect(within(row).getByRole('button', { name: nameStartingWith('Cancel') })).toBeDefined();
        expect(within(row).queryByRole('button', { name: nameStartingWith('Commit') })).toBeNull();
    });

    it('Given_a_realised_or_a_cancelled_flow_When_rendered_Then_neither_row_offers_any_action', async () => {
        const realised = { ...RENT, state: { status: 'REALISED', on: '2026-09-02' } };
        const cancelled = {
            ...SALARY,
            state: { status: 'CANCELLED', on: '2026-09-02', reason: 'No longer needed' },
        };
        fakeFetch(ledger([realised, cancelled]));
        renderFlows();

        await screen.findByRole('table', { name: 'Planned flows' });

        expect(within(rowOf('Rent')).queryByRole('button')).toBeNull();
        expect(within(rowOf('Salary')).queryByRole('button')).toBeNull();
    });

    it('Given_a_read_only_session_When_rendered_Then_no_row_offers_Commit_Realise_or_Cancel', async () => {
        fakeFetch(ledger([RENT, SALARY]));
        renderRoutes(planningRoutes, '/planning/flows', { access: { canWrite: false } });

        await screen.findByRole('table', { name: 'Planned flows' });

        expect(screen.queryByRole('button', { name: nameStartingWith('Commit') })).toBeNull();
        expect(screen.queryByRole('button', { name: nameStartingWith('Realise') })).toBeNull();
        expect(screen.queryByRole('button', { name: nameStartingWith('Cancel') })).toBeNull();
    });

    it('Given_a_planned_flow_When_committed_Then_it_posts_on_and_the_row_shows_the_Committed_badge_and_invalidates_only_flows', async () => {
        const today = TODAY;
        let flows: readonly unknown[] = [RENT];
        const requests = fakeFetch(
            lifecycleLedger(
                () => flows,
                (request) => {
                    if (
                        request.url === `${FLOWS_URL}/${RENT_ID}/commit` &&
                        request.method === 'POST'
                    ) {
                        const committed = { ...RENT, state: { status: 'COMMITTED', on: today } };
                        flows = [committed];
                        return jsonResponse(committed);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderFlows();
        queryClient.setQueryData(PROJECTION_KEY, {});
        queryClient.setQueryData(COMPARISON_KEY, {});
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Commit') }),
        );
        await user.click(screen.getByRole('button', { name: 'Commit' }));

        expect(await within(rowOf('Rent')).findByText('Committed')).toBeDefined();
        expect(
            requests
                .filter((request) => request.method === 'POST')
                .map(({ method, url, body }) => ({ method, url, body })),
        ).toEqual([{ method: 'POST', url: `${FLOWS_URL}/${RENT_ID}/commit`, body: { on: today } }]);
        // No ScenarioResponse field can change when a flow moves state, and a committed flow is
        // still projected, so only flows is invalidated - not a wider prefix, not scenarios, and
        // neither a projection nor a comparison.
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([planKeys.flows]);
        expect(queryClient.getQueryState(PROJECTION_KEY)?.isInvalidated).toBe(false);
        expect(queryClient.getQueryState(COMPARISON_KEY)?.isInvalidated).toBe(false);
    });

    it('Given_a_committed_flow_When_realised_Then_it_posts_on_and_the_row_shows_the_Realised_badge_and_invalidates_flows_projections_and_comparisons', async () => {
        const today = TODAY;
        let flows: readonly unknown[] = [SALARY];
        fakeFetch(
            lifecycleLedger(
                () => flows,
                (request) => {
                    if (
                        request.url === `${FLOWS_URL}/${SALARY_ID}/realise` &&
                        request.method === 'POST'
                    ) {
                        const realised = { ...SALARY, state: { status: 'REALISED', on: today } };
                        flows = [realised];
                        return jsonResponse(realised);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderFlows();
        queryClient.setQueryData(PROJECTION_KEY, {});
        queryClient.setQueryData(COMPARISON_KEY, {});
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Salary')).getByRole('button', { name: nameStartingWith('Realise') }),
        );
        await user.click(screen.getByRole('button', { name: 'Realise' }));

        expect(await within(rowOf('Salary')).findByText('Realised')).toBeDefined();
        // A realised flow no longer counts towards a projection, so every cached projection and
        // comparison is stale; no scenario field changes, so the scenarios list is not.
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([
            planKeys.flows,
            planKeys.projections,
            planKeys.comparisons,
        ]);
        expect(queryClient.getQueryState(PROJECTION_KEY)?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(COMPARISON_KEY)?.isInvalidated).toBe(true);
    });

    it('Given_a_planned_flow_When_cancelled_without_a_reason_Then_the_required_field_refuses_submission_and_nothing_is_sent', async () => {
        const requests = fakeFetch(ledger([RENT]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Cancel') }),
        );
        await user.click(screen.getByRole('button', { name: 'Cancel flow' }));

        const reasonField = screen.getByLabelText('Reason');
        expect(reasonField).toHaveProperty('validity.valueMissing', true);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
        // The panel stays open, unsubmitted: its own dismiss button is still on screen.
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeDefined();
    });

    it('Given_a_planned_flow_When_cancelled_with_a_reason_Then_it_posts_on_and_reason_and_shows_the_Cancelled_badge_and_invalidates_flows_projections_and_comparisons', async () => {
        const today = TODAY;
        let flows: readonly unknown[] = [RENT];
        const requests = fakeFetch(
            lifecycleLedger(
                () => flows,
                (request) => {
                    if (
                        request.url === `${FLOWS_URL}/${RENT_ID}/cancel` &&
                        request.method === 'POST'
                    ) {
                        const cancelled = {
                            ...RENT,
                            state: { status: 'CANCELLED', on: today, reason: 'No longer needed' },
                        };
                        flows = [cancelled];
                        return jsonResponse(cancelled);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderFlows();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Cancel') }),
        );
        await user.type(screen.getByLabelText('Reason'), 'No longer needed');
        await user.click(screen.getByRole('button', { name: 'Cancel flow' }));

        expect(await within(rowOf('Rent')).findByText('Cancelled')).toBeDefined();
        expect(
            requests
                .filter((request) => request.method === 'POST')
                .map(({ method, url, body }) => ({ method, url, body })),
        ).toEqual([
            {
                method: 'POST',
                url: `${FLOWS_URL}/${RENT_ID}/cancel`,
                body: { on: today, reason: 'No longer needed' },
            },
        ]);
        // A cancelled flow no longer counts towards a projection or a comparison either.
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([
            planKeys.flows,
            planKeys.projections,
            planKeys.comparisons,
        ]);
    });

    it('Given_a_409_response_When_an_action_is_submitted_Then_the_server_detail_is_shown_in_the_panel_and_it_stays_open', async () => {
        fakeFetch(
            ledger([RENT], (request) => {
                if (request.url === `${FLOWS_URL}/${RENT_ID}/commit` && request.method === 'POST') {
                    return problemResponse({
                        status: 409,
                        code: 'ILLEGAL_TRANSITION',
                        detail: 'The flow is no longer planned.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Commit') }),
        );
        await user.click(screen.getByRole('button', { name: 'Commit' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The flow is no longer planned.')).toBeDefined();
        // The panel stays open: its submit button, and the Date field, are still on screen.
        expect(screen.getByRole('button', { name: 'Commit' })).toBeDefined();
        expect(screen.getByLabelText('On')).toBeDefined();
        // On failure, focus moves to the alert - never to the state cell or the original button,
        // both of which belong to the success/cancel path.
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_a_400_naming_the_panels_date_When_an_action_is_submitted_Then_the_error_state_lists_it_under_the_On_label', async () => {
        fakeFetch(
            ledger([RENT], (request) => {
                if (request.url === `${FLOWS_URL}/${RENT_ID}/commit` && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'on', message: 'must not be null' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Commit') }),
        );
        await user.click(screen.getByRole('button', { name: 'Commit' }));

        const alert = await screen.findByRole('alert');
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['On: must not be null']);
    });

    // Every field of the panel, since each calls clearSettledOutcome on its own (frontend/web
    // CLAUDE.md: editing any field of a settled form clears its answer).
    for (const { field, edit } of [
        {
            field: 'reason',
            edit: (user) => user.type(screen.getByLabelText('Reason'), ' at all'),
        },
        {
            field: 'date',
            edit: () => changeValue('On', '2026-09-10'),
        },
    ] satisfies readonly FieldEdit[]) {
        it(`Given_a_refused_cancel_shown_in_its_panel_When_its_${field}_is_edited_Then_the_refusal_clears`, async () => {
            // The refusal answered the cancel as it was sent; once the owner changes it, the
            // message no longer describes anything in the panel.
            fakeFetch(
                ledger([RENT], (request) => {
                    if (
                        request.url === `${FLOWS_URL}/${RENT_ID}/cancel` &&
                        request.method === 'POST'
                    ) {
                        return problemResponse({
                            status: 422,
                            code: 'RULE_VIOLATED',
                            detail: 'A flow cannot be cancelled before it starts.',
                        });
                    }
                    return unexpectedRequest(request);
                }),
            );
            renderFlows();
            const user = userEvent.setup({ delay: null });
            await screen.findByRole('table', { name: 'Planned flows' });
            await user.click(
                within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Cancel') }),
            );
            await user.type(screen.getByLabelText('Reason'), 'No longer needed');
            await user.click(screen.getByRole('button', { name: 'Cancel flow' }));
            await screen.findByRole('alert');

            await edit(user);

            expect(screen.queryByRole('alert')).toBeNull();
        });
    }

    it('Given_a_panel_open_on_a_planned_row_When_cancelled_Then_focus_returns_to_the_button_that_opened_it', async () => {
        fakeFetch(ledger([RENT]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });
        const commitButton = within(rowOf('Rent')).getByRole('button', {
            name: nameStartingWith('Commit'),
        });

        await user.click(commitButton);
        await user.click(screen.getByRole('button', { name: 'Cancel' }));

        // Cancelling changes nothing about the flow, so PLANNED still offers Commit: the same
        // action is back, and focus returns to it.
        const restoredButton = within(rowOf('Rent')).getByRole('button', {
            name: nameStartingWith('Commit'),
        });
        expect(restoredButton).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(restoredButton);
        });
    });

    it('Given_a_panel_open_on_a_planned_row_When_committed_successfully_Then_focus_moves_to_the_rows_new_state_since_Commit_is_gone', async () => {
        const today = TODAY;
        let flows: readonly unknown[] = [RENT];
        fakeFetch(
            lifecycleLedger(
                () => flows,
                (request) => {
                    if (
                        request.url === `${FLOWS_URL}/${RENT_ID}/commit` &&
                        request.method === 'POST'
                    ) {
                        const committed = { ...RENT, state: { status: 'COMMITTED', on: today } };
                        flows = [committed];
                        return jsonResponse(committed);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });

        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Commit') }),
        );
        await user.click(screen.getByRole('button', { name: 'Commit' }));

        const committedText = await within(rowOf('Rent')).findByText('Committed');
        // The row no longer offers Commit - COMMITTED offers Realise and Cancel - so focus falls
        // back to the row's own state cell rather than a button that no longer exists.
        await waitFor(() => {
            expect(document.activeElement?.id).toBe(`flow-state-${RENT_ID}`);
        });
        expect(
            within(rowOf('Rent')).queryByRole('button', { name: nameStartingWith('Commit') }),
        ).toBeNull();
        expect(committedText).toBeDefined();
    });

    it('Given_a_lifecycle_panel_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before pending has rendered. The panel's own
        // pending check reads render-time state, so only its in-flight ref stops the second, and
        // a flow's lifecycle moves one way only (ADR-028: no double submit).
        const requests = fakeFetch(
            lifecycleLedger(
                () => [RENT],
                (request) =>
                    request.url === `${FLOWS_URL}/${RENT_ID}/commit` && request.method === 'POST'
                        ? pendingResponse()
                        : unexpectedRequest(request),
            ),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('table', { name: 'Planned flows' });
        await user.click(
            within(rowOf('Rent')).getByRole('button', { name: nameStartingWith('Commit') }),
        );
        const form = screen.getByLabelText('On').closest('form');
        if (form === null) {
            throw new Error('the On field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        // By the time pending has rendered, every request either submit started has reached fetch.
        await waitFor(() => {
            expect(screen.getByRole('button', { name: 'Commit' })).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });
});

describe('PlannedFlowsPage plan-flow form', () => {
    it('Given_a_read_only_session_When_the_page_renders_Then_the_plan_flow_form_is_not_offered_but_the_list_stays', async () => {
        fakeFetch(ledger([RENT]));
        renderRoutes(planningRoutes, '/planning/flows', { access: { canWrite: false } });

        expect(await screen.findByText('Rent')).toBeDefined();
        expect(screen.queryByRole('heading', { level: 2, name: 'Plan a flow' })).toBeNull();
        expect(screen.queryByLabelText('Description')).toBeNull();
    });

    it('Given_a_number_of_times_chosen_When_the_form_renders_Then_amount_every_and_times_state_their_formats', async () => {
        fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');

        expect(accessibleDescriptionOf(screen.getByLabelText('Amount'))).toBe(
            'A number with no commas, for example 1250.50',
        );
        for (const label of ['Every', 'Times']) {
            expect(accessibleDescriptionOf(screen.getByLabelText(label))).toBe(
                'A whole number, 1 or more',
            );
        }
    });

    it('Given_the_Repeats_field_When_Until_a_date_is_chosen_Then_cadence_interval_and_until_are_shown_but_not_times', async () => {
        fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.selectOptions(screen.getByLabelText('Repeats'), 'UNTIL');

        expect(screen.getByLabelText('Cadence')).toBeDefined();
        expect(screen.getByLabelText('Every')).toBeDefined();
        expect(screen.getByLabelText('Until')).toBeDefined();
        expect(screen.queryByLabelText('Times')).toBeNull();
    });

    it('Given_the_Repeats_field_When_A_number_of_times_is_chosen_Then_cadence_interval_and_times_are_shown_but_not_until', async () => {
        fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');

        expect(screen.getByLabelText('Cadence')).toBeDefined();
        expect(screen.getByLabelText('Every')).toBeDefined();
        expect(screen.getByLabelText('Times')).toBeDefined();
        expect(screen.queryByLabelText('Until')).toBeNull();
    });

    it('Given_an_every_of_zero_When_submitted_Then_native_validation_refuses_it_and_nothing_is_sent', async () => {
        const requests = fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
        await user.clear(screen.getByLabelText('Every'));
        await user.type(screen.getByLabelText('Every'), '0');
        await user.clear(screen.getByLabelText('Times'));
        await user.type(screen.getByLabelText('Times'), '12');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        expect(screen.getByLabelText('Every')).toHaveProperty('validity.patternMismatch', true);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    });

    it('Given_a_times_count_of_zero_When_submitted_Then_native_validation_refuses_it_and_nothing_is_sent', async () => {
        const requests = fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
        await user.clear(screen.getByLabelText('Times'));
        await user.type(screen.getByLabelText('Times'), '0');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        expect(screen.getByLabelText('Times')).toHaveProperty('validity.patternMismatch', true);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    });

    it('Given_an_every_of_zero_submitted_by_script_When_planned_Then_the_Every_field_says_why_and_takes_focus', async () => {
        // Native validation refuses "0" before a click reaches submit; a scripted submit does not,
        // and refusing it without a word would leave "Plan flow" doing nothing at all.
        const requests = fakeFetch(ledger([]));
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });
        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
        const every = screen.getByLabelText('Every');
        fireEvent.change(every, { target: { value: '0' } });
        fireEvent.change(screen.getByLabelText('Times'), { target: { value: '12' } });
        const form = every.closest('form');
        if (form === null) {
            throw new Error('the Every field is not inside a form');
        }

        fireEvent.submit(form);

        const error = await screen.findByText('Enter a whole number, 1 or more');
        expect(every.getAttribute('aria-invalid')).toBe('true');
        expect(every.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        await waitFor(() => {
            expect(document.activeElement).toBe(every);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    });

    it('Given_a_complete_times_recurrence_When_submitted_Then_it_posts_the_mapped_body', async () => {
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return jsonResponse(
                        {
                            id: 'e1e1e1e1-4d8c-4f3a-9a61-2f1c5e0d9b11',
                            description: 'Salary',
                            amount: { amount: '1000.00', currency: 'ILS' },
                            direction: 'INFLOW',
                            startsOn: TODAY,
                            recurrence: {
                                kind: 'TIMES',
                                cadence: 'MONTHLY',
                                interval: 1,
                                count: 12,
                            },
                            state: { status: 'PLANNED' },
                        },
                        201,
                    );
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.selectOptions(screen.getByLabelText('Direction'), 'INFLOW');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
        await user.clear(screen.getByLabelText('Times'));
        await user.type(screen.getByLabelText('Times'), '12');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        await waitFor(() => {
            expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        });
        expect(requests.find((request) => request.method === 'POST')?.body).toEqual({
            description: 'Salary',
            amount: { amount: '1000.00', currency: 'ILS' },
            direction: 'INFLOW',
            startsOn: TODAY,
            recurrence: { kind: 'TIMES', cadence: 'MONTHLY', interval: 1, count: 12 },
        });
    });

    it('Given_an_until_date_entered_When_submitted_Then_it_posts_the_mapped_body', async () => {
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return jsonResponse(
                        {
                            id: 'e2e2e2e2-4d8c-4f3a-9a61-2f1c5e0d9b11',
                            description: 'Subscription',
                            amount: { amount: '10.00', currency: 'ILS' },
                            direction: 'OUTFLOW',
                            startsOn: TODAY,
                            recurrence: {
                                kind: 'UNTIL',
                                cadence: 'WEEKLY',
                                interval: 2,
                                until: '2027-01-01',
                            },
                            state: { status: 'PLANNED' },
                        },
                        201,
                    );
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Subscription');
        await user.type(screen.getByLabelText('Amount'), '10.00');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'UNTIL');
        await user.selectOptions(screen.getByLabelText('Cadence'), 'WEEKLY');
        await user.clear(screen.getByLabelText('Every'));
        await user.type(screen.getByLabelText('Every'), '2');
        fireEvent.change(screen.getByLabelText('Until'), { target: { value: '2027-01-01' } });
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        await waitFor(() => {
            expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        });
        expect(requests.find((request) => request.method === 'POST')?.body).toEqual({
            description: 'Subscription',
            amount: { amount: '10.00', currency: 'ILS' },
            direction: 'OUTFLOW',
            startsOn: TODAY,
            recurrence: { kind: 'UNTIL', cadence: 'WEEKLY', interval: 2, until: '2027-01-01' },
        });
    });

    it('Given_times_fields_filled_When_Repeats_switches_to_Until_a_date_Then_the_posted_body_drops_count', async () => {
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return jsonResponse(
                        {
                            id: 'e3e3e3e3-4d8c-4f3a-9a61-2f1c5e0d9b11',
                            description: 'Subscription',
                            amount: { amount: '10.00', currency: 'ILS' },
                            direction: 'OUTFLOW',
                            startsOn: TODAY,
                            recurrence: {
                                kind: 'UNTIL',
                                cadence: 'MONTHLY',
                                interval: 1,
                                until: '2027-01-01',
                            },
                            state: { status: 'PLANNED' },
                        },
                        201,
                    );
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Subscription');
        await user.type(screen.getByLabelText('Amount'), '10.00');
        await user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES');
        await user.clear(screen.getByLabelText('Times'));
        await user.type(screen.getByLabelText('Times'), '12');

        await user.selectOptions(screen.getByLabelText('Repeats'), 'UNTIL');
        expect(screen.queryByLabelText('Times')).toBeNull();
        fireEvent.change(screen.getByLabelText('Until'), { target: { value: '2027-01-01' } });
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        await waitFor(() => {
            expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
        });
        const body = requests.find((request) => request.method === 'POST')?.body as {
            recurrence?: object;
        };
        expect(body.recurrence).not.toHaveProperty('count');
        expect(body.recurrence).toEqual({
            kind: 'UNTIL',
            cadence: 'MONTHLY',
            interval: 1,
            until: '2027-01-01',
        });
    });

    it('Given_an_incomplete_recurrence_400_When_planned_Then_it_falls_to_the_alert_as_an_unmapped_field', async () => {
        fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [
                            {
                                field: 'recurrence.completeForKind',
                                message: "must carry exactly its kind's members",
                            },
                        ],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Invalid request content.')).toBeDefined();
        // The rule is about the recurrence as a whole, which the Repeats field chooses.
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(["Repeats: must carry exactly its kind's members"]);
    });

    it('Given_a_400_naming_amount_amount_When_planned_Then_the_message_is_on_the_Amount_field', async () => {
        // Reachable through the UI: MoneyDto's own @Pattern refuses anything but plain decimal
        // text, such as a thousands separator, and the free-text Amount field does not either.
        fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'amount.amount', message: 'must be a decimal number' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1,000.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        await screen.findByText('must be a decimal number');
        // The format hint describes the field first, then the server's refusal.
        expect(accessibleDescriptionOf(screen.getByLabelText('Amount'))).toBe(
            'A number with no commas, for example 1250.50 must be a decimal number',
        );
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Amount'));
        });
    });

    it('Given_a_400_listing_description_and_direction_errors_When_planned_Then_each_message_is_on_its_field', async () => {
        fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [
                            { field: 'description', message: 'must not be blank' },
                            { field: 'direction', message: 'must not be null' },
                        ],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const descriptionError = await screen.findByText('must not be blank');
        expect(screen.getByLabelText('Description').getAttribute('aria-describedby')).toBe(
            descriptionError.id,
        );
        const directionError = screen.getByText('must not be null');
        expect(screen.getByLabelText('Direction').getAttribute('aria-describedby')).toBe(
            directionError.id,
        );
        expect(screen.queryByRole('alert')).toBeNull();
        // The first invalid field in the form's order takes focus, not the first field.
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Description'));
        });
    });

    it('Given_a_400_listing_a_startsOn_error_When_planned_Then_the_message_is_on_the_Starts_on_field', async () => {
        fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'startsOn', message: 'must not be null' }],
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const startsOnError = await screen.findByText('must not be null');
        expect(screen.getByLabelText('Starts on').getAttribute('aria-describedby')).toBe(
            startsOnError.id,
        );
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Starts on'));
        });
    });

    it('Given_a_negative_amount_When_planned_Then_it_is_sent_as_typed_and_a_422_shows_the_detail', async () => {
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 422,
                        code: 'RULE_VIOLATED',
                        detail: 'The amount must be positive.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Rent');
        await user.type(screen.getByLabelText('Amount'), '-250.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The amount must be positive.')).toBeDefined();
        const post = requests.find((request) => request.method === 'POST');
        expect((post?.body as { amount?: { amount?: string } } | undefined)?.amount?.amount).toBe(
            '-250.00',
        );
    });

    it('Given_the_ledger_refuses_the_write_as_FORBIDDEN_When_a_flow_is_planned_Then_the_fixed_sentence_is_shown_instead_of_its_detail', async () => {
        // The server enforces access whatever the client hides (ADR-032), and a refusal never
        // tells the user which permission it was (ErrorState's fixed sentence).
        fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 403,
                        code: 'FORBIDDEN',
                        detail: 'Role viewer lacks ledger:write.',
                    });
                }
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });
        await user.type(screen.getByLabelText('Description'), 'Rent');
        await user.type(screen.getByLabelText('Amount'), '250.00');

        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const alert = await screen.findByRole('alert');
        expect(alert.textContent).toContain("You don't have permission to see this.");
        expect(alert.textContent).not.toContain('ledger:write');
    });

    // Every field of the form, since each calls clearSettledOutcome on its own (frontend/web
    // CLAUDE.md: editing any field of a settled form clears its answer). `arrange` first shows the
    // fields only a recurrence offers, so the refusal is on screen beside them.
    for (const { field, arrange, edit } of [
        { field: 'amount', edit: (user) => user.clear(screen.getByLabelText('Amount')) },
        {
            field: 'description',
            edit: (user) => user.type(screen.getByLabelText('Description'), ' due'),
        },
        {
            field: 'currency',
            edit: (user) => user.selectOptions(screen.getByLabelText('Currency'), 'USD'),
        },
        {
            field: 'direction',
            edit: (user) => user.selectOptions(screen.getByLabelText('Direction'), 'INFLOW'),
        },
        { field: 'start_date', edit: () => changeValue('Starts on', '2026-10-01') },
        {
            field: 'recurrence',
            edit: (user) => user.selectOptions(screen.getByLabelText('Repeats'), 'TIMES'),
        },
        {
            field: 'cadence',
            arrange: repeatThreeTimes,
            edit: (user) => user.selectOptions(screen.getByLabelText('Cadence'), 'WEEKLY'),
        },
        {
            field: 'interval',
            arrange: repeatThreeTimes,
            edit: (user) => user.type(screen.getByLabelText('Every'), '2'),
        },
        {
            field: 'count',
            arrange: repeatThreeTimes,
            edit: (user) => user.type(screen.getByLabelText('Times'), '0'),
        },
        {
            field: 'end_date',
            arrange: async (user) => {
                await user.selectOptions(screen.getByLabelText('Repeats'), 'UNTIL');
                await changeValue('Until', '2027-09-01');
            },
            edit: () => changeValue('Until', '2027-10-01'),
        },
    ] satisfies readonly FieldEdit[]) {
        it(`Given_a_422_refusal_shown_When_the_${field}_is_edited_Then_the_refusal_clears`, async () => {
            // The refusal answered the flow that was sent; kept on screen, it would describe a flow
            // the owner has since corrected.
            fakeFetch(
                ledger([], (request) => {
                    if (request.url === FLOWS_URL && request.method === 'POST') {
                        return problemResponse({
                            status: 422,
                            code: 'RULE_VIOLATED',
                            detail: 'The amount must be positive.',
                        });
                    }
                    return unexpectedRequest(request);
                }),
            );
            renderFlows();
            const user = userEvent.setup({ delay: null });
            await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });
            await user.type(screen.getByLabelText('Description'), 'Rent');
            await user.type(screen.getByLabelText('Amount'), '-250.00');
            await arrange?.(user);
            await user.click(screen.getByRole('button', { name: 'Plan flow' }));
            await screen.findByRole('alert');

            await edit(user);

            expect(screen.queryByRole('alert')).toBeNull();
        });
    }

    it('Given_a_flow_planned_successfully_When_the_list_shows_it_Then_the_form_is_reset_focus_is_on_its_description_and_only_flows_is_invalidated', async () => {
        const NEW_ID = 'f1f1f1f1-4d8c-4f3a-9a61-2f1c5e0d9b11';
        let served: readonly unknown[] = [];
        fakeFetch(
            lifecycleLedger(
                () => served,
                (request) => {
                    if (request.url === FLOWS_URL && request.method === 'POST') {
                        const created = {
                            id: NEW_ID,
                            description: 'Salary',
                            amount: { amount: '1000.00', currency: 'ILS' },
                            direction: 'INFLOW',
                            startsOn: TODAY,
                            recurrence: { kind: 'ONCE' },
                            state: { status: 'PLANNED' },
                        };
                        served = [created];
                        return jsonResponse(created, 201);
                    }
                    return unexpectedRequest(request);
                },
            ),
        );
        const { queryClient } = renderFlows();
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.selectOptions(screen.getByLabelText('Direction'), 'INFLOW');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        await screen.findByRole('rowheader', { name: 'Salary' });
        await waitFor(() => {
            // The focus target is the description's own span, not the <th> it sits inside: a
            // table header cell carries no interactive semantics of its own to focus.
            expect(document.activeElement).toBe(within(rowOf('Salary')).getByText('Salary'));
        });
        expect(screen.getByLabelText('Description')).toHaveProperty('value', '');
        expect(screen.getByLabelText('Amount')).toHaveProperty('value', '');
        // Planning a flow cannot change any existing scenario - a new flow's id names no
        // scenario yet - so only flows is invalidated.
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([planKeys.flows]);
    });

    it('Given_an_open_request_in_flight_When_submitted_again_Then_submit_is_disabled_and_nothing_is_sent_twice', async () => {
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST')
                    return pendingResponse();
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });

        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Plan flow' }));

        const submit = screen.getByRole('button', { name: 'Plan flow' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        const form = screen.getByLabelText('Description').closest('form');
        if (form === null) {
            throw new Error('the Description field is not inside a form');
        }
        // A disabled button stops a click, not a submit event from elsewhere, such as Enter.
        fireEvent.submit(form);
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard that reads render-time state lets both through.
        const requests = fakeFetch(
            ledger([], (request) => {
                if (request.url === FLOWS_URL && request.method === 'POST')
                    return pendingResponse();
                return unexpectedRequest(request);
            }),
        );
        renderFlows();
        const user = userEvent.setup({ delay: null });
        await screen.findByRole('heading', { level: 2, name: 'Plan a flow' });
        await user.type(screen.getByLabelText('Description'), 'Salary');
        await user.type(screen.getByLabelText('Amount'), '1000.00');
        const form = screen.getByLabelText('Description').closest('form');
        if (form === null) {
            throw new Error('the Description field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        // By the time pending has rendered, every request either submit started has reached fetch.
        const submit = screen.getByRole('button', { name: 'Plan flow' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });
});

describe('planning index route', () => {
    it('Given_the_planning_index_When_rendered_Then_it_redirects_to_flows', async () => {
        fakeFetch(ledger([]));

        renderRoutes(planningRoutes, '/planning');

        expect(await screen.findByText('No planned flows yet')).toBeDefined();
        expect(
            screen
                .getByRole('navigation', { name: 'Planning' })
                .querySelector('[aria-current="page"]')?.textContent,
        ).toBe('Flows');
    });
});
