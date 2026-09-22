// @vitest-environment jsdom
// A top-level navigation is a jsdom `window.location` call, which needs a DOM global to exist.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { type LogRecord, setLogSink } from '@/shared/lib/logger';
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
    let written: LogRecord[] = [];
    let restoreSink: () => void = () => undefined;

    beforeEach(() => {
        written = [];
        restoreSink = setLogSink((record) => written.push(record));
    });

    afterEach(() => {
        restoreSink();
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

    // The `URL` constructor normalises a protocol to lowercase and strips leading whitespace and
    // tab/newline characters before comparing it, so these three are still `javascript:` under
    // `new URL(url).protocol` and must be refused exactly like the plain lower-case form above.
    // A naive check such as `url.startsWith('http')` would let every one of these through, which
    // is exactly the regression these three exist to catch.
    it('Given_a_mixed_case_javascript_url_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign('JavaScript:alert(1)');

        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_javascript_url_with_leading_whitespace_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign(' javascript:alert(1)');

        expect(assign).not.toHaveBeenCalled();
    });

    it('Given_a_javascript_url_with_a_tab_injected_into_the_scheme_When_assign_is_called_Then_the_browser_does_not_navigate', () => {
        const assign = spyOnAssign();

        browserLocation.assign('java\tscript:alert(1)');

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

    // The one caller that can pass a value here that is not a fixed constant is the gateway's
    // `logoutUrl`, which carries `id_token_hint` as a query parameter (ADR-030). This is
    // unreachable today - `Origin` refuses a non-http(s) base at gateway start-up - but a refused
    // value must never reach the console verbatim regardless, since `url` is not one of
    // logger.ts's redacted key fragments (nor should it be: nearly every other call site in this
    // client logs a benign API path under that same name).
    it('Given_a_refused_url_carrying_a_token_When_assign_is_called_Then_the_token_never_reaches_the_log', () => {
        spyOnAssign();
        const secret = 'super-secret-id-token-value';
        const tokenBearingUrl = `javascript:alert(1)?id_token_hint=${secret}`;

        browserLocation.assign(tokenBearingUrl);

        expect(JSON.stringify(written)).not.toContain(secret);
        expect(JSON.stringify(written)).not.toContain(tokenBearingUrl);
    });

    it('Given_a_refused_url_When_assign_is_called_Then_only_its_scheme_is_logged', () => {
        spyOnAssign();

        browserLocation.assign('javascript:alert(1)');

        expect(written).toHaveLength(1);
        expect(written[0]).toMatchObject({ level: 'error', scheme: 'javascript:' });
    });
});
