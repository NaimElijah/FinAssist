// @vitest-environment jsdom
// Reads window.location.search and renders a link, both of which need a DOM.

import { render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SignIn } from './SignIn';

/** jsdom's real `Location`, restored after every test so no test leaks a search string. */
const REAL_LOCATION = window.location;

/**
 * Replace `window.location` with a stand-in carrying only `search`: `SignIn` reads nothing else
 * from `Location`, and spreading a class instance such as jsdom's would lose its prototype.
 */
function setSearch(search: string): void {
    Object.defineProperty(window, 'location', {
        value: { search },
        writable: true,
        configurable: true,
    });
}

describe('SignIn', () => {
    afterEach(() => {
        Object.defineProperty(window, 'location', {
            value: REAL_LOCATION,
            writable: true,
            configurable: true,
        });
        document.title = '';
    });

    it('Given_no_query_string_When_rendered_Then_a_main_region_headed_sign_in_titles_the_document', async () => {
        setSearch('');
        render(<SignIn />);

        const main = screen.getByRole('main');
        expect(within(main).getByRole('heading', { level: 1, name: 'Sign in' })).toBeDefined();
        await waitFor(() => {
            expect(document.title).toBe('Sign in · FinAssist');
        });
    });

    it('Given_signin_failed_in_the_query_string_When_rendered_Then_the_explanation_is_inside_the_main_region', () => {
        setSearch('?signin=failed');
        render(<SignIn />);

        expect(screen.getByRole('main').contains(screen.getByRole('alert'))).toBe(true);
    });

    it('Given_no_query_string_When_rendered_Then_it_links_to_the_gateways_login_entry', () => {
        setSearch('');
        render(<SignIn />);

        const link = screen.getByRole('link', { name: /sign in/i });
        expect(link.getAttribute('href')).toBe('/bff/oauth2/authorization/keycloak');
    });

    it('Given_no_query_string_When_rendered_Then_no_failure_explanation_is_shown', () => {
        setSearch('');
        render(<SignIn />);

        expect(screen.queryByRole('alert')).toBeNull();
    });

    // A plain <a>, not a fetch or a client-side navigate: only a top-level browser navigation
    // follows the gateway's redirect to Keycloak's own login page.
    it('Given_the_link_When_read_Then_it_is_a_native_anchor_element', () => {
        setSearch('');
        render(<SignIn />);

        expect(screen.getByRole('link', { name: /sign in/i }).tagName).toBe('A');
    });

    it('Given_signin_failed_in_the_query_string_When_rendered_Then_an_explanation_is_shown', () => {
        setSearch('?signin=failed');
        render(<SignIn />);

        expect(screen.getByRole('alert').textContent).toMatch(/failed/i);
    });

    it('Given_an_unrelated_query_string_When_rendered_Then_no_failure_explanation_is_shown', () => {
        setSearch('?other=1');
        render(<SignIn />);

        expect(screen.queryByRole('alert')).toBeNull();
    });
});
