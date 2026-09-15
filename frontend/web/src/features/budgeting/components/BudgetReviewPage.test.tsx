// @vitest-environment jsdom
// Renders the review screen through the feature's own routes, so it needs a DOM. fetch is
// stubbed, so the feature's real api/ module and its mapping run under test.

import { screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { setLogSink } from '@/shared/lib/logger';
import {
    fakeFetch,
    jsonResponse,
    pendingResponse,
    problemResponse,
    unexpectedRequest,
} from '@/shared/testing/fakeFetch';
import { renderRoutes } from '@/shared/testing/renderRoutes';

import { budgetingRoutes } from '../index';

const BUDGETS_URL = '/ledger/api/v1/budgets';

const SEPTEMBER_BUDGET = {
    id: '0b7d2a4e-4d8c-4f3a-9a61-2f1c5e0d9b11',
    period: { start: '2026-09-01', end: '2026-10-01' },
    currency: 'ILS',
    limits: [{ category: 'groceries', limit: { amount: '1000.00', currency: 'ILS' } }],
    total: { amount: '1000.00', currency: 'ILS' },
} as const;

const REVIEW = {
    budget: SEPTEMBER_BUDGET.id,
    period: SEPTEMBER_BUDGET.period,
    currency: 'ILS',
    categories: [
        {
            category: 'groceries',
            limit: { amount: '1000.00', currency: 'ILS' },
            spent: { amount: '1200.00', currency: 'ILS' },
            remaining: { amount: '-200.00', currency: 'ILS' },
            overspent: true,
        },
    ],
    unbudgeted: [
        { category: 'gifts', spent: { amount: '50.00', currency: 'ILS' } },
        { category: 'hobbies', spent: { amount: '30.00', currency: 'ILS' } },
    ],
    uncategorised: { amount: '15.00', currency: 'ILS' },
} as const;

function reviewUrl(budgetId: string): string {
    return `${BUDGETS_URL}/${budgetId}/review`;
}

function renderReview(budgetId: string) {
    return renderRoutes(budgetingRoutes, `/budgets/${budgetId}`);
}

let restoreSink: () => void = () => undefined;

beforeEach(() => {
    restoreSink = setLogSink(() => undefined);
});

afterEach(() => {
    restoreSink();
});

describe('BudgetReviewPage', () => {
    it('Given_a_budget_and_its_review_When_the_page_renders_Then_the_header_shows_the_period_label_and_currency', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) return jsonResponse(REVIEW);
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'September 2026' }),
        ).toBeDefined();
        expect(screen.getByText('ILS')).toBeDefined();
    });

    it('Given_an_overspent_category_When_the_page_renders_Then_its_status_reads_Overspent_and_remaining_is_shown_as_received', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) return jsonResponse(REVIEW);
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        const row = (await screen.findByRole('rowheader', { name: 'groceries' })).closest('tr');
        if (row === null) {
            throw new Error('no row is headed by groceries');
        }
        expect(within(row).getByText('Overspent')).toBeDefined();
        expect(within(row).getByText('-₪200.00')).toBeDefined();
    });

    it('Given_a_category_within_its_limit_When_the_page_renders_Then_its_status_reads_Within_limit', async () => {
        const withinLimit = {
            ...REVIEW,
            categories: [
                {
                    category: 'rent',
                    limit: { amount: '4000.00', currency: 'ILS' },
                    spent: { amount: '4000.00', currency: 'ILS' },
                    remaining: { amount: '0.00', currency: 'ILS' },
                    overspent: false,
                },
            ],
        };
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) return jsonResponse(withinLimit);
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        const row = (await screen.findByRole('rowheader', { name: 'rent' })).closest('tr');
        if (row === null) {
            throw new Error('no row is headed by rent');
        }
        expect(within(row).getByText('Within limit')).toBeDefined();
    });

    it('Given_unbudgeted_spend_When_the_page_renders_Then_each_category_and_its_spend_are_listed', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) return jsonResponse(REVIEW);
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        const gifts = (await screen.findByRole('rowheader', { name: 'gifts' })).closest('tr');
        const hobbies = screen.getByRole('rowheader', { name: 'hobbies' }).closest('tr');
        if (gifts === null || hobbies === null) {
            throw new Error('unbudgeted rows missing');
        }
        expect(within(gifts).getByText('₪50.00')).toBeDefined();
        expect(within(hobbies).getByText('₪30.00')).toBeDefined();
    });

    it('Given_no_unbudgeted_spend_When_the_page_renders_Then_the_unbudgeted_section_shows_its_empty_state', async () => {
        const noUnbudgeted = { ...REVIEW, unbudgeted: [] };
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) return jsonResponse(noUnbudgeted);
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'September 2026' }),
        ).toBeDefined();
        expect(await screen.findByText(/no unbudgeted spend/i)).toBeDefined();
    });

    it('Given_uncategorised_spend_When_the_page_renders_Then_the_amount_is_shown_even_when_it_is_zero', async () => {
        const zeroUncategorised = { ...REVIEW, uncategorised: { amount: '0.00', currency: 'ILS' } };
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) {
                return jsonResponse(zeroUncategorised);
            }
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        expect(await screen.findByText('₪0.00')).toBeDefined();
    });

    it('Given_the_review_fails_When_the_page_renders_Then_an_error_state_offers_a_retry_that_refetches', async () => {
        let failing = true;
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url !== reviewUrl(SEPTEMBER_BUDGET.id)) return unexpectedRequest(request);
            return failing
                ? problemResponse({
                      status: 503,
                      code: 'UNAVAILABLE',
                      detail: 'The ledger is restarting.',
                  })
                : jsonResponse(REVIEW);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        const alert = await screen.findByRole('alert');
        expect(within(alert).getByText('The ledger is restarting.')).toBeDefined();
        failing = false;
        await userEvent.setup().click(within(alert).getByRole('button', { name: 'Try again' }));

        expect(await screen.findByRole('rowheader', { name: 'groceries' })).toBeDefined();
    });

    it('Given_the_budget_is_missing_from_the_list_While_its_review_has_not_yet_settled_Then_it_is_still_treated_as_not_found', async () => {
        // A defensive path distinct from the 404 case above: the list has already loaded and
        // does not hold the id, even though the review request itself has not answered yet.
        const otherId = 'ffffffff-4d8c-4f3a-9a61-2f1c5e0d9b11';
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(otherId)) return pendingResponse();
            return unexpectedRequest(request);
        });

        renderReview(otherId);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Budget not found' }),
        ).toBeDefined();
    });

    it('Given_a_404_BUDGET_NOT_FOUND_When_the_page_renders_Then_a_not_found_message_links_back_to_budgets', async () => {
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(SEPTEMBER_BUDGET.id)) {
                return problemResponse({ status: 404, code: 'BUDGET_NOT_FOUND' });
            }
            return unexpectedRequest(request);
        });

        renderReview(SEPTEMBER_BUDGET.id);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Budget not found' }),
        ).toBeDefined();
        expect(
            (await screen.findByRole('link', { name: 'Back to Budgets' })).getAttribute('href'),
        ).toBe('/budgets');
    });

    it('Given_an_id_missing_from_the_loaded_budgets_list_When_the_page_renders_Then_it_is_treated_as_not_found', async () => {
        // The review request for an id the ledger holds no budget for also answers
        // BUDGET_NOT_FOUND; not-found here is proven by the list lacking the id regardless.
        const otherId = 'ffffffff-4d8c-4f3a-9a61-2f1c5e0d9b11';
        fakeFetch((request) => {
            if (request.url === BUDGETS_URL) return jsonResponse([SEPTEMBER_BUDGET]);
            if (request.url === reviewUrl(otherId)) {
                return problemResponse({ status: 404, code: 'BUDGET_NOT_FOUND' });
            }
            return unexpectedRequest(request);
        });

        renderReview(otherId);

        expect(
            await screen.findByRole('heading', { level: 1, name: 'Budget not found' }),
        ).toBeDefined();
    });
});
