// @vitest-environment node
// The logger touches no DOM. Keeping it out of jsdom keeps the suite honest about what the
// code under test actually depends on.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { type LogRecord, log, redactFields, REDACTED, setLogSink } from './logger';

describe('redaction', () => {
    it('Given_a_secret_named_field_When_redacted_Then_its_value_is_replaced', () => {
        expect(redactFields({ apiKey: 'abc', password: 'hunter2' })).toEqual({
            apiKey: REDACTED,
            password: REDACTED,
        });
    });

    it('Given_a_hyphenated_header_name_When_redacted_Then_it_is_still_matched', () => {
        // HTTP spells it X-Api-Key and JavaScript spells it apiKey; a boundary catching only
        // one of those leaks the other.
        expect(redactFields({ 'X-Api-Key': 'abc', Authorization: 'Bearer abc' })).toEqual({
            'X-Api-Key': REDACTED,
            Authorization: REDACTED,
        });
    });

    it('Given_an_ordinary_field_When_redacted_Then_it_survives', () => {
        expect(redactFields({ url: 'https://ledger.test', status: 500 })).toEqual({
            url: 'https://ledger.test',
            status: 500,
        });
    });

    it('Given_llm_token_counts_When_redacted_Then_they_survive', () => {
        // The assistance feature exists to report usage. A bare 'token' fragment would redact
        // the measurement while protecting nothing.
        expect(redactFields({ inputTokens: 1200, output_tokens: 340 })).toEqual({
            inputTokens: 1200,
            output_tokens: 340,
        });
    });

    it('Given_a_real_bearer_token_field_When_redacted_Then_it_is_removed', () => {
        expect(redactFields({ accessToken: 'abc', refresh_token: 'def' })).toEqual({
            accessToken: REDACTED,
            refresh_token: REDACTED,
        });
    });

    it('Given_a_secret_nested_in_a_payload_When_redacted_Then_it_is_still_removed', () => {
        const redacted = redactFields({
            request: { headers: { authorization: 'Bearer abc' }, path: '/v1/accounts' },
        });

        expect(redacted).toEqual({
            request: { headers: { authorization: REDACTED }, path: '/v1/accounts' },
        });
    });

    it('Given_a_secret_inside_an_array_When_redacted_Then_it_is_still_removed', () => {
        expect(
            redactFields({ attempts: [{ accessToken: 'abc' }, { accessToken: 'def' }] }),
        ).toEqual({
            attempts: [{ accessToken: REDACTED }, { accessToken: REDACTED }],
        });
    });

    it('Given_a_cyclic_object_When_redacted_Then_it_terminates', () => {
        // Without a depth bound this recurses until the stack runs out, turning a log call
        // into a crash - strictly worse than the missing line it was meant to write.
        const cyclic: Record<string, unknown> = { name: 'loop' };
        cyclic.self = cyclic;

        expect(() => redactFields(cyclic)).not.toThrow();
    });
});

describe('records', () => {
    let written: LogRecord[] = [];
    let restore: () => void = () => undefined;

    beforeEach(() => {
        written = [];
        restore = setLogSink((record) => written.push(record));
    });

    afterEach(() => {
        // Restored per test rather than swapped for the file: the sink is module state, and
        // leaving it replaced would silently swallow every record in whatever runs next.
        restore();
    });

    it('Given_an_event_When_logged_Then_the_record_carries_level_and_timestamp', () => {
        log.info('something happened', { url: 'https://ledger.test' });

        expect(written).toHaveLength(1);
        expect(written[0]).toMatchObject({
            level: 'info',
            event: 'something happened',
            url: 'https://ledger.test',
        });
        expect(Date.parse(String(written[0]?.timestamp))).not.toBeNaN();
    });

    it('Given_a_secret_field_When_logged_Then_the_record_never_carries_the_value', () => {
        log.error('outbound call', { authToken: 'super-secret-value' });

        expect(JSON.stringify(written)).not.toContain('super-secret-value');
    });

    it('Given_a_field_named_like_the_metadata_When_logged_Then_the_metadata_wins', () => {
        // Spreading caller fields last let `{ event: 'click' }` replace the stable event name
        // every filter and alert keys on, and `{ level: 'debug' }` downgrade an error.
        log.error('http request failed', { event: 'click', level: 'debug', timestamp: 'nope' });

        expect(written[0]).toMatchObject({
            event: 'http request failed',
            level: 'error',
        });
        expect(Date.parse(String(written[0]?.timestamp))).not.toBeNaN();
    });

    it('Given_each_level_When_logged_Then_the_record_reports_it', () => {
        log.debug('d');
        log.info('i');
        log.warn('w');
        log.error('e');

        expect(written.map((record) => record.level)).toEqual(['debug', 'info', 'warn', 'error']);
    });
});
