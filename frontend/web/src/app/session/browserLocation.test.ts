// @vitest-environment jsdom
// A top-level navigation is a jsdom `window.location` call, which needs a DOM global to exist.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { browserLocation } from './browserLocation';

/** jsdom's real `Location`, restored after every test so no test leaks a stand-in to the next. */
const REAL_LOCATION = window.location;

/**
 * Replace `window.location.assign` with a spy that never actually navigates, so a test can
 * assert on it without jsdom logging an unimplemented-navigation error to the console.
 *
 * jsdom's `location.assign` is not a configurable own property, so `vi.spyOn` cannot wrap it in
 * place; the whole `location` object is replaced with a stand-in carrying only a mock `assign` -
 * nothing under test here reads any other member of `Location`, and spreading a class instance
 * such as jsdom's `Location` would lose its prototype anyway.
 */
function spyOnAssign(): ReturnType<typeof vi.fn> {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
        value: { assign },
        writable: true,
        configurable: true,
    });
    return assign;
}

describe('browserLocation', () => {
    afterEach(() => {
        Object.defineProperty(window, 'location', {
            value: REAL_LOCATION,
            writable: true,
            configurable: true,
        });
    });

    it('Given_an_http_url_When_assign_is_called_Then_the_browser_navigates_there', () => {
        const assign = spyOnAssign();

        browserLocation.assign('http://localhost:5173/');

        expect(assign).toHaveBeenCalledExactlyOnceWith('http://localhost:5173/');
    });

    it('Given_an_https_url_When_assign_is_called_Then_the_browser_navigates_there', () => {
        const assign = spyOnAssign();

        browserLocation.assign(
            'https://localhost:8081/realms/finassist/protocol/openid-connect/logout',
        );

        expect(assign).toHaveBeenCalledExactlyOnceWith(
            'https://localhost:8081/realms/finassist/protocol/openid-connect/logout',
        );
    });

    // The deliberate case: a tampered or malformed logoutUrl must never reach the browser as a
    // script that executes in the page, rather than a navigation away from it.
    it('Given_a_javascript_url_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign('javascript:alert(1)');

        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_data_url_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign('data:text/html,<script>alert(1)</script>');

        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_value_that_is_not_a_url_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign('not a url');

        expect(assign).not.toHaveBeenCalled();
    });
});
