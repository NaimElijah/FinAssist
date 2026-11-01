// @vitest-environment node
// crypto.getRandomValues is a Node global; no DOM is involved.

import { describe, expect, it } from 'vitest';

import { newSpanId, newTraceId, newTraceparent, traceIdOf } from './correlation';

const TRACEPARENT = /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/;

describe('ids', () => {
    it('Given_a_new_trace_id_When_generated_Then_it_is_32_lowercase_hex_characters', () => {
        expect(newTraceId()).toMatch(/^[0-9a-f]{32}$/);
    });

    it('Given_a_new_span_id_When_generated_Then_it_is_16_lowercase_hex_characters', () => {
        expect(newSpanId()).toMatch(/^[0-9a-f]{16}$/);
    });

    it('Given_two_trace_ids_When_generated_Then_they_differ', () => {
        // A constant id would correlate every request in the system into one trace, which is
        // indistinguishable from no correlation at all.
        expect(newTraceId()).not.toEqual(newTraceId());
    });
});

describe('traceparent', () => {
    it('Given_no_trace_When_a_header_is_built_Then_it_matches_the_w3c_shape', () => {
        expect(newTraceparent()).toMatch(TRACEPARENT);
    });

    it('Given_an_existing_trace_When_a_header_is_built_Then_that_trace_is_carried', () => {
        const traceId = '4bf92f3577b34da6a3ce929d0e0e4736';

        expect(newTraceparent(traceId)).toMatch(new RegExp(`^00-${traceId}-[0-9a-f]{16}-01$`));
    });

    it('Given_a_header_When_parsed_Then_the_trace_id_is_returned', () => {
        expect(traceIdOf('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01')).toBe(
            '4bf92f3577b34da6a3ce929d0e0e4736',
        );
    });

    it.each([
        ['an_empty_string', ''],
        ['undefined', undefined],
        ['null', null],
        ['prose', 'not-a-header'],
        ['a_short_trace_id', '00-abc-00f067aa0ba902b7-01'],
        ['a_non_hex_trace_id', '00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-00f067aa0ba902b7-01'],
        ['too_few_fields', '00-4bf92f3577b34da6a3ce929d0e0e4736'],
        // The W3C format reserves the all-zero id as invalid, and the assistant refuses it too.
        ['an_all_zero_trace_id', `00-${'0'.repeat(32)}-00f067aa0ba902b7-01`],
    ])('Given_%s_When_parsed_Then_null_is_returned', (_name, value) => {
        expect(traceIdOf(value)).toBeNull();
    });

    it('Given_a_generated_header_When_parsed_Then_the_round_trip_holds', () => {
        const traceId = newTraceId();

        expect(traceIdOf(newTraceparent(traceId))).toBe(traceId);
    });
});
