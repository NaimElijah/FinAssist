// @vitest-environment jsdom
// Renders the categorisation-rules screen through the feature's own routes and drives its form,
// so it needs a DOM. fetch is stubbed, so the feature's real api/ module and its mapping run
// under test.

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
import type { FieldEdit } from '@/shared/testing/fieldEdits';
import { focusedElementHolds } from '@/shared/testing/focus';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { budgetKeys, categorisationRuleKeys } from '../hooks/budgetQueries';
import { budgetingRoutes } from '../index';

const RULES_URL = '/ledger/api/v1/categorisation-rules';

const RULES = [
    { fragment: 'coffee', category: 'dining' },
    { fragment: 'rent', category: 'housing' },
];

function renderRules() {
    return renderRoutes(budgetingRoutes, '/budgets/rules');
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('CategorisationRulesPage routing', () => {
    it('Given_the_static_rules_path_When_matched_Then_it_renders_the_rules_page_rather_than_the_dynamic_budget_route', async () => {
        // /budgets/rules and /budgets/:budgetId are the same depth, so they genuinely compete for
        // a match here (unlike accounting's two-segment record-transaction path). React Router
        // ranks a static segment above a dynamic one regardless of array order, so this static
        // route always wins; a colliding match would instead try to review a budget "rules".
        fakeFetch((request) =>
            request.url === RULES_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderRules();

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Categorisation rules' }),
        ).toBeDefined();
        expect(screen.queryByRole('heading', { level: 1, name: 'Budget not found' })).toBeNull();
    });
});

describe('CategorisationRulesPage', () => {
    it('Given_rules_served_in_an_order_When_the_page_renders_Then_the_list_keeps_that_order', async () => {
        fakeFetch((request) =>
            request.url === RULES_URL ? jsonResponse(RULES) : unexpectedRequest(request),
        );

        renderRules();

        const list = await screen.findByRole('list');
        expect(
            within(list)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['"coffee" → dining', '"rent" → housing']);
    });

    it('Given_a_read_only_session_When_the_page_renders_Then_the_add_form_is_not_offered_but_the_list_stays', async () => {
        fakeFetch((request) =>
            request.url === RULES_URL ? jsonResponse(RULES) : unexpectedRequest(request),
        );

        renderRoutes(budgetingRoutes, '/budgets/rules', { access: { canWrite: false } });

        await screen.findByRole('list');
        expect(screen.queryByRole('button', { name: 'Add rule' })).toBeNull();
        expect(screen.queryByLabelText('Fragment')).toBeNull();
        expect(screen.getByText('"coffee" → dining')).toBeDefined();
    });

    it('Given_the_page_When_rendered_Then_the_ordering_hint_is_visible', async () => {
        fakeFetch((request) =>
            request.url === RULES_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderRules();

        expect(
            await screen.findByText(
                'Rules are tried in the order they were added; the first match wins.',
            ),
        ).toBeDefined();
    });

    it('Given_no_rules_When_the_page_renders_Then_the_empty_state_explains_spend_stays_uncategorised', async () => {
        fakeFetch((request) =>
            request.url === RULES_URL ? jsonResponse([]) : unexpectedRequest(request),
        );

        renderRules();

        expect(
            await screen.findByText('No rules yet — spend stays uncategorised until one matches'),
        ).toBeDefined();
        expect(screen.queryByRole('list')).toBeNull();
    });

    it('Given_the_list_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        const requests = fakeFetch((request) => {
            if (request.url !== RULES_URL) return unexpectedRequest(request);
            return failing
                ? problemResponse({
                      status: 502,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  })
                : jsonResponse(RULES);
        });

        renderRules();

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        failing = false;
        await userEvent
            .setup({ delay: null })
            .click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByText('"coffee" → dining')).toBeDefined();
        expect(requests.filter((request) => request.url === RULES_URL)).toHaveLength(2);
        // The retry button went with the failure, so focus lands on what the retry restored.
        await waitFor(() => {
            expect(focusedElementHolds(screen.getByText('"coffee" → dining'))).toBe(true);
        });
    });
});

describe('CategorisationRulesPage adding a rule', () => {
    it('Given_the_form_When_a_rule_is_added_Then_it_posts_the_exact_json_body_and_the_refetched_list_shows_it', async () => {
        const served: unknown[] = [];
        const requests = fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                served.push({ fragment: 'coffee', category: 'dining' });
                return jsonResponse({ fragment: 'coffee', category: 'dining' }, 201);
            }
            if (request.url === RULES_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        renderRules();
        await screen.findByText('No rules yet — spend stays uncategorised until one matches');
        const user = userEvent.setup({ delay: null });

        await user.type(screen.getByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        expect(await screen.findByText('"coffee" → dining')).toBeDefined();
        const posts = requests
            .filter((request) => request.method === 'POST')
            // Projected before comparing: RecordedRequest also carries headers, which this
            // assertion is not about, and toEqual demands an exact match on every field it is
            // given - unlike toMatchObject, which would stop checking body for an unplanned
            // extra field anywhere in its object graph.
            .map(({ method, url, body }) => ({ method, url, body }));
        expect(posts).toEqual([
            { method: 'POST', url: RULES_URL, body: { fragment: 'coffee', category: 'dining' } },
        ]);
        expect(
            requests.filter((request) => request.method === 'GET' && request.url === RULES_URL),
        ).toHaveLength(2);
    });

    it('Given_a_rule_added_When_it_appears_Then_the_form_is_reset', async () => {
        // The list starts empty and only gains the rule once the POST has actually landed, so
        // `findByText` below can only resolve once the add has really happened - not trivially
        // from an initial load that already included it.
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                served.push({ fragment: 'coffee', category: 'dining' });
                return jsonResponse({ fragment: 'coffee', category: 'dining' }, 201);
            }
            if (request.url === RULES_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        await screen.findByText('"coffee" → dining');
        expect(screen.getByLabelText('Fragment')).toHaveProperty('value', '');
        expect(screen.getByLabelText('Category')).toHaveProperty('value', '');
    });

    it('Given_a_rule_is_added_When_the_list_shows_it_Then_focus_moves_to_its_list_item', async () => {
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                served.push({ fragment: 'coffee', category: 'dining' });
                return jsonResponse({ fragment: 'coffee', category: 'dining' }, 201);
            }
            if (request.url === RULES_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        const item = await screen.findByText('"coffee" → dining');
        await waitFor(() => {
            expect(document.activeElement).toBe(item);
        });
    });

    it('Given_the_refetch_after_adding_a_rule_fails_When_it_settles_Then_focus_moves_to_the_Fragment_field_instead_of_a_stale_rule', async () => {
        // The list still shows its pre-add data (TanStack Query keeps it on a failed background
        // refetch), so the "last" row is the stale one - landing focus there would be silently
        // wrong. A visible failure above the list, plus focus on the add form's own first field,
        // is the fallback CategorisationRulesPage reaches for instead.
        let refetchesServed = 0;
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return jsonResponse({ fragment: 'tea', category: 'dining' }, 201);
            }
            if (request.url === RULES_URL) {
                refetchesServed += 1;
                if (refetchesServed === 1)
                    return jsonResponse([{ fragment: 'coffee', category: 'dining' }]);
                return problemResponse({
                    status: 503,
                    code: 'UNAVAILABLE',
                    detail: 'The ledger is restarting.',
                });
            }
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });
        await screen.findByText('"coffee" → dining');

        await user.type(screen.getByLabelText('Fragment'), 'tea');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        await screen.findByRole('alert');
        expect(screen.queryByText('"tea" → dining')).toBeNull();
        expect(screen.getByText('"coffee" → dining')).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Fragment'));
        });
    });

    it('Given_a_400_naming_a_field_the_form_lacks_When_added_Then_the_error_state_lists_each_field_by_its_label_or_wire_name', async () => {
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [
                        { field: 'category', message: 'must not be blank' },
                        { field: 'addCategorisationRuleRequest', message: 'is not readable' },
                    ],
                });
            }
            if (request.url === RULES_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'cafe');
        await user.type(screen.getByLabelText('Category'), ' ');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        const alert = await screen.findByRole('alert');
        expect(
            within(alert)
                .getAllByRole('listitem')
                .map((item) => item.textContent),
        ).toEqual(['Category: must not be blank', 'addCategorisationRuleRequest: is not readable']);
    });

    it('Given_a_400_naming_the_fragment_field_When_added_Then_its_message_is_the_Fragment_fields_error', async () => {
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [{ field: 'fragment', message: 'must not be blank' }],
                });
            }
            if (request.url === RULES_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), ' ');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        const error = await screen.findByText('must not be blank');
        expect(screen.getByLabelText('Fragment').getAttribute('aria-describedby')).toBe(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Fragment'));
        });
    });

    // Every field of the form, since each calls clearSettledOutcome on its own (frontend/web
    // CLAUDE.md: editing any field of a settled form clears its answer).
    for (const { field, edit } of [
        {
            field: 'fragment',
            edit: (user) => user.type(screen.getByLabelText('Fragment'), 'coffee'),
        },
        {
            field: 'category',
            edit: (user) => user.type(screen.getByLabelText('Category'), ' out'),
        },
    ] satisfies readonly FieldEdit[]) {
        it(`Given_a_field_error_on_Fragment_When_the_${field}_is_edited_Then_the_error_clears`, async () => {
            // The message answered the rule that was sent; once the owner changes it, it no
            // longer describes the field it sits under.
            fakeFetch((request) => {
                if (request.url === RULES_URL && request.method === 'POST') {
                    return problemResponse({
                        status: 400,
                        code: 'MALFORMED_REQUEST',
                        detail: 'Invalid request content.',
                        errors: [{ field: 'fragment', message: 'must not be blank' }],
                    });
                }
                if (request.url === RULES_URL) return jsonResponse([]);
                return unexpectedRequest(request);
            });
            renderRules();
            const user = userEvent.setup({ delay: null });
            await user.type(await screen.findByLabelText('Fragment'), ' ');
            await user.type(screen.getByLabelText('Category'), 'dining');
            await user.click(screen.getByRole('button', { name: 'Add rule' }));
            await screen.findByText('must not be blank');

            await edit(user);

            expect(screen.queryByText('must not be blank')).toBeNull();
            expect(screen.getByLabelText('Fragment').getAttribute('aria-invalid')).toBeNull();
        });
    }

    it('Given_a_400_naming_the_category_field_When_added_Then_its_message_is_the_Category_fields_error', async () => {
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'Invalid request content.',
                    errors: [{ field: 'category', message: 'must not be blank' }],
                });
            }
            if (request.url === RULES_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), ' ');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        const error = await screen.findByText('must not be blank');
        expect(screen.getByLabelText('Category').getAttribute('aria-describedby')).toBe(error.id);
        expect(screen.queryByRole('alert')).toBeNull();
        await waitFor(() => {
            expect(document.activeElement).toBe(screen.getByLabelText('Category'));
        });
    });

    it('Given_a_400_with_nothing_to_place_on_a_field_When_added_Then_focus_moves_to_the_error_state', async () => {
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return problemResponse({
                    status: 400,
                    code: 'MALFORMED_REQUEST',
                    detail: 'The request body could not be read.',
                });
            }
            if (request.url === RULES_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The request body could not be read.')).toBeDefined();
        await waitFor(() => {
            expect(document.activeElement).toBe(alert);
        });
    });

    it('Given_a_filled_form_When_it_is_submitted_twice_before_it_renders_again_Then_one_request_is_sent', async () => {
        // Two submit events can arrive in one tick, before the pending state has rendered, so a
        // guard that reads render-time state lets both through. Counting requests right after
        // firing them would not prove anything either way - mutate() only reaches fetch after
        // TanStack's own internal awaits - so this waits for the pending state to actually
        // render first, by which time every request either submit started has reached fetch.
        const requests = fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                return pendingResponse();
            }
            if (request.url === RULES_URL) return jsonResponse([]);
            return unexpectedRequest(request);
        });
        renderRules();
        const user = userEvent.setup({ delay: null });
        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        const form = screen.getByLabelText('Fragment').closest('form');
        if (form === null) {
            throw new Error('the Fragment field is not inside a form');
        }

        fireEvent.submit(form);
        fireEvent.submit(form);

        const submit = screen.getByRole('button', { name: 'Add rule' });
        await waitFor(() => {
            expect(submit).toHaveProperty('disabled', true);
        });
        expect(requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    });

    it('Given_the_cache_holds_the_rules_list_and_two_budget_reviews_When_a_rule_is_added_Then_only_those_three_queries_are_invalidated', async () => {
        // A new rule can change any budget's review, so every review key is invalidated, however
        // many budgets exist; the budgets list cannot change from a rule, so it must not be.
        const served: unknown[] = [];
        fakeFetch((request) => {
            if (request.url === RULES_URL && request.method === 'POST') {
                served.push({ fragment: 'coffee', category: 'dining' });
                return jsonResponse({ fragment: 'coffee', category: 'dining' }, 201);
            }
            if (request.url === RULES_URL) return jsonResponse([...served]);
            return unexpectedRequest(request);
        });
        const { queryClient } = renderRules();
        queryClient.setQueryData(budgetKeys.list, []);
        queryClient.setQueryData(budgetKeys.review('b1'), {});
        queryClient.setQueryData(budgetKeys.review('b2'), {});
        // The rules list is actively observed by this very page, so once its own refetch
        // settles its `isInvalidated` flag resets - a state check would race that reset. The
        // spy instead records every call `invalidateQueries` ever received, so the assertion
        // below cannot be timing-dependent.
        const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
        const user = userEvent.setup({ delay: null });

        await user.type(await screen.findByLabelText('Fragment'), 'coffee');
        await user.type(screen.getByLabelText('Category'), 'dining');
        await user.click(screen.getByRole('button', { name: 'Add rule' }));

        await screen.findByText('"coffee" → dining');
        const invalidatedKeys = invalidateSpy.mock.calls.map(([options]) => options?.queryKey);
        expect(invalidatedKeys).toEqual(
            expect.arrayContaining([categorisationRuleKeys.list, budgetKeys.reviews]),
        );
        expect(invalidatedKeys).not.toContainEqual(budgetKeys.list);
        expect(invalidatedKeys).toHaveLength(2);
        // The two review keys were never actively observed on this page, so nothing refetches
        // them and their invalidated flag stays put - safe to assert directly.
        expect(queryClient.getQueryState(budgetKeys.review('b1'))?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(budgetKeys.review('b2'))?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(budgetKeys.list)?.isInvalidated).toBe(false);
    });
});
