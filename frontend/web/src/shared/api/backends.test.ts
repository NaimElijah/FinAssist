// @vitest-environment node
// A pure function over a plain record: no DOM needed.

import { describe, expect, it } from 'vitest';

import { GATEWAY_PORT_VARIABLE, gatewayTarget } from './backends';

describe('gatewayTarget', () => {
    it('Given_no_GATEWAY_PORT_When_resolved_Then_it_targets_the_default_port_8090', () => {
        expect(gatewayTarget({})).toBe('http://127.0.0.1:8090');
    });

    it('Given_an_empty_GATEWAY_PORT_When_resolved_Then_it_targets_the_default_port_as_Compose_does', () => {
        // Compose publishes `${GATEWAY_PORT:-8090}`, which reads an empty value as unset.
        expect(gatewayTarget({ [GATEWAY_PORT_VARIABLE]: '' })).toBe('http://127.0.0.1:8090');
    });

    it('Given_a_GATEWAY_PORT_When_resolved_Then_it_targets_that_port_on_loopback', () => {
        expect(gatewayTarget({ [GATEWAY_PORT_VARIABLE]: '18090' })).toBe('http://127.0.0.1:18090');
    });

    it('Given_the_highest_port_When_resolved_Then_it_is_accepted', () => {
        expect(gatewayTarget({ [GATEWAY_PORT_VARIABLE]: '65535' })).toBe('http://127.0.0.1:65535');
    });

    for (const value of ['abc', '0', '65536', '80.5', '-1', ' 8090', '8090x', '1e3']) {
        it(`Given_GATEWAY_PORT_${JSON.stringify(value)}_When_resolved_Then_it_fails_naming_the_variable`, () => {
            // A port that is not one would otherwise surface only as every proxied call failing.
            expect(() => gatewayTarget({ [GATEWAY_PORT_VARIABLE]: value })).toThrow(
                /^GATEWAY_PORT must be a port number from 1 to 65535/,
            );
        });
    }
});
