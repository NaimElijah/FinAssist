// @vitest-environment jsdom
// Rendering is the whole point here, so this one does need a DOM.

import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { type LogRecord, setLogSink } from '../shared/lib/logger';
import { ErrorBoundary } from './ErrorBoundary';

function Exploding(): never {
    throw new Error('component blew up');
}

describe('ErrorBoundary', () => {
    let written: LogRecord[] = [];
    let restoreSink: () => void = () => undefined;

    beforeEach(() => {
        written = [];
        restoreSink = setLogSink((record) => written.push(record));
        // React logs the caught error itself, which is noise here and not what is under test.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);
    });

    afterEach(() => {
        restoreSink();
        vi.restoreAllMocks();
    });

    it('Given_a_healthy_child_When_rendered_Then_it_is_shown', () => {
        render(
            <ErrorBoundary>
                <p>all is well</p>
            </ErrorBoundary>,
        );

        expect(screen.getByText('all is well')).toBeDefined();
    });

    it('Given_a_throwing_child_When_rendered_Then_the_fallback_replaces_it', () => {
        render(
            <ErrorBoundary>
                <Exploding />
            </ErrorBoundary>,
        );

        expect(screen.getByRole('alert').textContent).toBe('Something went wrong.');
    });

    it('Given_a_custom_fallback_When_a_child_throws_Then_that_fallback_is_used', () => {
        render(
            <ErrorBoundary fallback={<p>bespoke message</p>}>
                <Exploding />
            </ErrorBoundary>,
        );

        expect(screen.getByText('bespoke message')).toBeDefined();
    });

    it('Given_a_throwing_child_When_rendered_Then_the_failure_is_logged_once', () => {
        // Without this the tree unmounts and the page goes blank with nothing written
        // anywhere, which is the silent failure the boundary exists to prevent.
        render(
            <ErrorBoundary>
                <Exploding />
            </ErrorBoundary>,
        );

        const failures = written.filter((record) => record.event === 'render failed');
        expect(failures).toHaveLength(1);
        expect(failures[0]).toMatchObject({ level: 'error', reason: 'component blew up' });
    });
});
