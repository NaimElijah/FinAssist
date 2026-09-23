// @vitest-environment jsdom
// Renders the budgets screen through the feature's own routes and drives its form, so it needs a
// DOM. fetch is stubbed, so the feature's real api/ module and its mapping run under test.

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { focusedElementHolds } from '@/shared/testing/focus';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { budgetKeys } from '../hooks/budgetQueries';
import { budgetingRoutes } from '../index';

const BUDGETS_URL = '/ledger/api/v1/budgets';

const SEPTEMBER_BUDGET = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    period: { start: '2026-09-01', end: '2026-10-01' },
    currency: 'ILS',
    limits: [{ category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } }],
    total: { amount: '1000.00', currency: 'ILS' },
} as const;

const CUSTOM_PERIOD_BUDGET = {
    id: '5c0e8f7a-1b2d-4e3f-8a9b-0c1d2e3f4a5b',
    period: { start: '2026-09-01', end: '2026-09-20' },
    currency: 'USD',
    limits: [{ category: 'travel', limit: { amount: '300.00', currency: 'USD' } }],
    total: { amount: '300.00', currency: 'USD' },
} as const;

function renderBudgets() {
    return renderRoutes(budgetingRoutes, '/budgets');
}

/** The table row a budget's period cell heads. */
function rowOf(name: string): HTMLElement {
    const row = screen.getByRole('rowheader', { name }).closest('tr');
    if (row === null) {
        throw new Error(`no table row is headed by ${name}`);
    }
    return row;
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    // The client logs every failed request; several tests fail requests on purpose.
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('BudgetsPage', () => {
    it('Given_budgets_served_in_an_order_When_the_page_renders_Then_rows_keep_that_order_with_the_month_label_and_total', async () => {
        fakeFetch((request) =>
            request.url === BUDGETS_URL
                ? jsonResponse([SEPTEMBER_BUDGET, CUSTOM_PERIOD_BUDGET])
                : unexpectedRequest(request),
        );

        renderBudgets();

        expect(await screen.findByRole('heading', { level: 1, name: 'Budgets' })).toBeDefined();
        await screen.findByRole('table', { name: 'Budgets' });
        // The single-month period reads as a month label; the 1-20 September period is not one,
        // so it reads as its start and display end (20 September's exclusive end minus a day).
        expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
            'September 2026',
            '1 Sept 2026 – 19 Sept 2026',
        ]);
        expect(await within(rowOf('September 2026')).findByText('₪1,000.00')).toBeDefined();
        expect(within(rowOf('September 2026')).getByText('ILS')).toBeDefined();
        expect(within(rowOf('1 Sept 2026 – 19 Sept 2026')).getByText('US$300.00')).toBeDefined();
        expect(
            screen
                .getAllByRole('link', { name: 'Review' })
                .map((link) => link.getAttribute('href')),
        ).toEqual([`/budgets/${SEPTEMBER_BUDGET.id}`, `/budgets/${CUSTOM_PERIOD_BUDGET.id}`]);
    });

    it('Given_a_read_only_session_When_the_page_renders_Then_the_define_form_is_not_offered_but_the_list_and_the_rules_link_stay', async () => {
        fakeFetch((request) =>
            request.url === BUDGETS_URL
                ? jsonResponse([SEPTEMBER_BUDGET])
                : unexpectedRequest(request),
        );

        renderRoutes(budgetingRoutes, '/budgets', { access: { canWrite: false } });

        await screen.findByRole('heading', { level: 1, name: 'Budgets' });
        expect(screen.queryByRole('button', { name: 'Define budget' })).toBeNull();
        expect(screen.queryByLabelText('Month')).toBeNull();
        expect(await screen.findByRole('link', { name: 'Review' })).toBeDefined();
        expect(screen.getByRole('link', { name: 'Categorisation rules' })).toBeDefined();
    });

    it('Given_the_page_When_rendered_Then_a_link_reaches_the_categorisation_rules_screen', async () => {
        fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderBudgets();

        expect(
            (await screen.findByRole('link', { name: 'Categorisation rules' })).getAttribute(
                'href',
            ),
        ).toBe('/budgets/rules');
    });

    it('Given_no_budgets_When_the_page_renders_Then_the_empty_state_invites_defining_one', async () => {
        fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderBudgets();

        expect(await screen.findByText('No budgets yet — define one below')).toBeDefined();
        expect(screen.queryByRole('table')).toBeNull();
    });

    it('Given_the_list_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url !== BUDGETS_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({
                      status: 502,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  })
                : jsonResponse([]);
        });

        renderBudgets();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('No budgets yet — define one below')).toBeDefined();
        expect(requests.filter((request) => request.url === BUDGETS_URL)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on what the retry restored.
        await waitFor(() => {
            expect(focusedElementHolds(screen.getByText('No budgets yet — define one below'))).toBe(
                true,
            );
        });
    });
});

describe('BudgetsPage defining a budget', () => {
    /** Pick a month, currency, and fill one limit row. */
    async function defineSeptemberGroceries(user: ReturnType<typeof userEvent.setup>) {
        const month = await screen.findByLabelText('Month');
        fireEvent.change(month, { target: { value: '2026-09' } });
        await user.selectOptions(screen.getByLabelText('Currency'), 'ILS');
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Limit amount 1'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Define budget' }));
    }

    it('Given_the_form_When_a_budget_with_two_limits_is_defined_Then_it_posts_the_exact_json_body_and_the_refetched_list_shows_it', async () => {
        const served: unknown[] = [];
        const requests = fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                served.push(SEPTEMBER_BUDGET);
                return jsonResponse(SEPTEMBER_BUDGET, 201);
            }
            if (request.url === BUDGETS_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        await screen.findByText('No budgets yet — define one below');
        const user = userEvent.setup({ delay: null });

        const month = screen.getByLabelText('Month');
        fireEvent.change(month, { target: { value: '2026-09' } });
        await user.selectOptions(screen.getByLabelText('Currency'), 'ILS');
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Limit amount 1'), '1000.00');
        await user.click(screen.getByRole('button', { name: 'Add category' }));
        await user.type(screen.getByLabelText('Category 2'), 'rent');
        await user.type(screen.getByLabelText('Limit amount 2'), '4000.00');
        await user.click(screen.getByRole('button', { name: 'Define budget' }));

        expect(await screen.findByRole('link', { name: 'Review' })).toBeDefined();
        const posts = requests
            .filter((request) => request.method === 'POST')
            // Projected before comparing: RecordedRequest also carries headers, which this
            // assertion is not about, and toEqual demands an exact match on every field it is
            // given - unlike toMatchObject, which would stop checking body for an unplanned
            // extra field anywhere in its object graph.
            .map(({ method, url, body }) => ({ method, url, body }));
        expect(posts).toEqual([
            {
                method: 'POST',
                url: BUDGETS_URL,
                body: {
                    period: { start: '2026-09-01', end: '2026-10-01' },
                    currency: 'ILS',
                    limits: [
                        { category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } },
                        { category: 'rent', limit: { amount: '4000.00', currency: 'ILS' } },
                    ],
                },
            },
        ]);
        expect(
            requests.filter((request) => request.method === 'GET' && request.url === BUDGETS_URL),
        ).toHaveLength(2);
    });

    it('Given_a_cached_budget_review_When_a_budget_is_defined_Then_the_list_is_invalidated_and_the_review_is_not', async () => {
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                served.push(SEPTEMBER_BUDGET);
                return jsonResponse(SEPTEMBER_BUDGET, 201);
            }
            if (request.url === BUDGETS_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        const { queryClient } = renderBudgets();
        await screen.findByText('No budgets yet — define one below');
        // Seeded rather than read through the page, which renders no review of its own; a
        // defined budget cannot be the one this review is for (its id does not exist yet), so
        // this stands in for any budget already reviewed elsewhere.
        queryClient.setQueryData(budgetKeys.review(CUSTOM_PERIOD_BUDGET.id), {});
        // The list is actively rendered on this very page, so its own `isInvalidated` flag
        // resets the moment its refetch settles - a state check on it would race that reset.
        // The spy instead records every call `invalidateQueries` ever received.
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });

        await defineSeptemberGroceries(user);

        await screen.findByRole('link', { name: 'Review' });
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual([budgetKeys.list]);
        // Never actively observed on this page, so nothing refetches it and the flag stays put.
        expect(
            queryClient.getQueryState(budgetKeys.review(CUSTOM_PERIOD_BUDGET.id))?.isInvalidated,
        ).toBe(false);
    });

    it('Given_a_defined_budget_When_the_list_shows_it_Then_the_form_is_reset_and_focus_is_on_its_review_link', async () => {
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                served.push(SEPTEMBER_BUDGET);
                return jsonResponse(SEPTEMBER_BUDGET, 201);
            }
            if (request.url === BUDGETS_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });

        await defineSeptemberGroceries(user);

        const link = await screen.findByRole('link', { name: 'Review' });
        await waitFor(() => {
            expect(document.activeElement).toBe(link);
        });
        expect(screen.getByLabelText('Category 1')).toHaveProperty('value', '');
        expect(screen.getByLabelText('Limit amount 1')).toHaveProperty('value', '');
    });

    it('Given_a_rule_the_domain_refuses_When_defined_Then_its_detail_is_shown_in_an_error_state', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 422,
                    code: 'RULE_VIOLATED',
                    detail: 'A budget needs at least one limit.',
                });
            }
            if (request.url === BUDGETS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });

        await defineSeptemberGroceries(user);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('A budget needs at least one limit.')).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_a_refusal_shown_When_a_limit_is_edited_Then_the_refusal_clears', async () => {
        // The refusal answered the limits that were sent; kept on screen, it would describe
        // limits the owner has since changed.
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 422,
                    code: 'RULE_VIOLATED',
                    detail: 'A limit must be positive.',
                });
            }
            if (request.url === BUDGETS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });
        await defineSeptemberGroceries(user);
        await screen.findByRole('alert');

        await user.type(screen.getByLabelText('Limit amount 1'), '0');

        expect(screen.queryByRole('alert')).toBeNull();
    });

    it('Given_a_400_naming_the_currency_field_When_defined_Then_its_message_is_the_Currency_fields_error', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [{ field: 'currency', message: 'is not supported' }],
                });
            }
            if (request.url === BUDGETS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });

        await defineSeptemberGroceries(user);

        const error = await screen.findByText('is not supported');
        expect(screen.getByLabelText('Currency').getAttribute('aria-describedby')).toBe(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Currency'));
        });
    });

    it('Given_a_400_naming_a_field_inside_one_limit_row_When_defined_Then_the_whole_problem_is_an_error_state', async () => {
        // A row's own fields are not among the form's top-level fields, mirroring
        // RecordTransactionForm's entries: an unmapped path is never silently dropped.
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [{ field: 'limits[0].category', message: 'must not be blank' }],
                });
            }
            if (request.url === BUDGETS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });

        await defineSeptemberGroceries(user);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('Invalid request content.')).toBeDefined();
        expect(screen.getByLabelText('Category 1').getAttribute('aria-invalid')).toBeNull();
    });

    it('Given_one_limit_row_When_rendered_Then_its_remove_control_is_disabled', async () => {
        fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderBudgets();

        expect(await screen.findByRole('button', { name: 'Remove limit 1' })).toHaveProperty(
            'disabled',
            true,
        );
    });

    it('Given_a_second_row_added_and_filled_When_the_first_row_is_removed_Then_the_second_rows_values_survive_keyed_by_its_stable_id', async () => {
        // Row state is keyed by a stable id rather than position, so removing row 1 must not
        // reset or shuffle what was typed into row 2 - it becomes the new row 1's values.
        fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );
        renderBudgets();
        const user = userEvent.setup({ delay: null });

        await user.click(await screen.findByRole('button', { name: 'Add category' }));
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Category 2'), 'rent');
        await user.click(screen.getByRole('button', { name: 'Remove limit 1' }));

        expect(screen.queryByLabelText('Category 2')).toBeNull();
        expect(screen.getByLabelText('Category 1')).toHaveProperty('value', 'rent');
    });

    it('Given_an_invalid_month_bypassing_native_validation_When_submitted_Then_the_form_is_not_locked_and_nothing_is_posted', async () => {
        // A real month picker, and the field's own `pattern`, keep this unreachable through the
        // UI; this proves the fallback is still safe when either is bypassed (a scripted value,
        // or a text-fallback browser with no month-picker UI and no pattern support).
        const requests = fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );
        renderBudgets();
        const user = userEvent.setup({ delay: null });
        const month = await screen.findByLabelText('Month');
        fireEvent.change(month, { target: { value: '2026-13' } });
        await user.selectOptions(screen.getByLabelText('Currency'), 'ILS');
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Limit amount 1'), '1000.00');
        const form = month.closest('form');
        if (form === null) {
            throw new Error('the Month field is not inside a form');
        }

        fireEvent.submit(form);

        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
        expect(screen.getByRole('button', { name: 'Define budget' })).toHaveProperty(
            'disabled',
            false,
        );
    });

    it('Given_a_month_the_form_cannot_read_When_submitted_Then_the_Month_field_says_why_and_takes_focus', async () => {
        // A month input accepts a year of five digits, such as 20266-09, and ignores `pattern`, so
        // a value the period cannot be read from reaches submit. Refusing it without a word would
        // leave "Define budget" doing nothing at all.
        const requests = fakeFetch((request) =>
            request.url === BUDGETS_URL ? jsonResponse([]) : unexpectedRequest(request),
        );
        renderBudgets();
        const user = userEvent.setup({ delay: null });
        const month = await screen.findByLabelText('Month');
        fireEvent.change(month, { target: { value: '20266-09' } });
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Limit amount 1'), '1000.00');
        const form = month.closest('form');
        if (form === null) {
            throw new Error('the Month field is not inside a form');
        }

        fireEvent.submit(form);

        const error = await screen.findByText('Enter a month as YYYY-MM');
        expect(month.getAttribute('aria-invalid')).toBe('true');
        expect(month.getAttribute('aria-describedby')?.split(' ')).toContain(error.id);
        await waitFor(() => {
            expect(document.activeElement).toBe(month);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0);
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard that reads render-time state lets both through. Counting requests right after
        // firing them would not prove anything either way - mutate() only reaches fetch after
        // TanStack's own internal awaits - so this waits for the pending state to actually
        // render first, by which time every request either submit started has reached fetch.
        const requests = fakeFetch((request) => {
            if (request.url === BUDGETS_URL && request.method === 'POST') {
                return pendingResponse();
            }
            if (request.url === BUDGETS_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderBudgets();
        const user = userEvent.setup({ delay: null });
        const month = await screen.findByLabelText('Month');
        fireEvent.change(month, { target: { value: '2026-09' } });
        await user.selectOptions(screen.getByLabelText('Currency'), 'ILS');
        await user.type(screen.getByLabelText('Category 1'), 'groceries');
        await user.type(screen.getByLabelText('Limit amount 1'), '1000.00');
        const form = month.closest('form');
        if (form === null) {
            throw new Error('the Month field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        const submit = screen.getByRole('button', { name: 'Define budget' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });
});
