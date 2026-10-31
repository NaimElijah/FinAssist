// @vitest-environment jsdom
// Reads the rendered status region, so it needs a DOM.

import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LoadingNote } from './LoadingNote';

/** How long the note waits before it takes its message; the component's own delay, restated. */
const MESSAGE_DELAY_MS = 100;

beforeEach(() => {
    // Only the timer pair the note uses, so React's own scheduling stays real and the count of
    // pending timers is the note's alone.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
});

afterEach(() => {
    vi.useRealTimers();
});

describe('LoadingNote', () => {
    it('Given_a_message_When_its_delay_passes_Then_a_status_region_says_it', () => {
        render(<LoadingNote message="Loading accounts…" />);

        act(() => {
            vi.advanceTimersByTime(MESSAGE_DELAY_MS);
        });

        expect(screen.getByRole('status').textContent).toBe('Loading accounts…');
    });

    it('Given_a_message_When_first_rendered_Then_its_status_region_is_empty_until_the_same_region_takes_the_message', () => {
        // A live region announces a change to what it holds, so it must be on the page before its
        // text arrives; one inserted with its text already in it is often read out by nothing.
        render(<LoadingNote message="Loading accounts…" />);
        const region = screen.getByRole('status');
        expect(region.textContent).toBe('');

        act(() => {
            vi.advanceTimersByTime(MESSAGE_DELAY_MS - 1);
        });
        expect(region.textContent).toBe('');

        act(() => {
            vi.advanceTimersByTime(1);
        });
        expect(region.textContent).toBe('Loading accounts…');
        expect(screen.getByRole('status')).toBe(region);
    });

    it('Given_a_read_answering_before_the_delay_When_the_note_unmounts_Then_no_timer_is_left_behind', () => {
        // A timer outliving the note would set state on an unmounted component.
        const { unmount } = render(<LoadingNote message="Loading accounts…" />);
        expect(vi.getTimerCount()).toBe(1);

        unmount();

        expect(vi.getTimerCount()).toBe(0);
    });
});
