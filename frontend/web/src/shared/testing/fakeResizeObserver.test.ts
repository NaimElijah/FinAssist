// @vitest-environment jsdom
// Observes DOM elements, so it needs a DOM.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { FakeResizeObserver, isObserved, resizeElement } from './fakeResizeObserver';

/** An observer whose callback the test can watch. */
function watchingObserver() {
    const callback = vi.fn<ResizeObserverCallback>();
    return { observer: new FakeResizeObserver(callback), callback };
}

let observers: FakeResizeObserver[] = [];

afterEach(() => {
    for (const observer of observers) {
        observer.disconnect();
    }
    observers = [];
});

/** `watchingObserver`, disconnected when the test finishes so none outlives it. */
function trackedObserver() {
    const watching = watchingObserver();
    observers.push(watching.observer);
    return watching;
}

describe('FakeResizeObserver', () => {
    it('Given_an_observed_element_When_it_is_resized_Then_the_observer_is_called_back_with_its_entry', () => {
        const { observer, callback } = trackedObserver();
        const element = document.createElement('div');
        observer.observe(element);

        resizeElement(element);

        expect(callback).toHaveBeenCalledTimes(1);
        const [entries, calledWith] = callback.mock.calls[0] ?? [];
        expect(entries?.map((entry) => entry.target)).toEqual([element]);
        expect(calledWith).toBe(observer);
    });

    it('Given_an_element_nobody_observes_When_it_is_resized_Then_no_observer_is_called_back', () => {
        const { observer, callback } = trackedObserver();
        observer.observe(document.createElement('div'));

        resizeElement(document.createElement('div'));

        expect(callback).not.toHaveBeenCalled();
    });

    it('Given_an_unobserved_element_When_it_is_resized_Then_its_former_observer_is_not_called_back', () => {
        const { observer, callback } = trackedObserver();
        const element = document.createElement('div');
        observer.observe(element);
        observer.unobserve(element);

        resizeElement(element);

        expect(callback).not.toHaveBeenCalled();
        expect(isObserved(element)).toBe(false);
    });

    it('Given_a_disconnected_observer_When_its_elements_are_resized_Then_it_is_not_called_back', () => {
        const { observer, callback } = trackedObserver();
        const first = document.createElement('div');
        const second = document.createElement('table');
        observer.observe(first);
        observer.observe(second);

        observer.disconnect();
        resizeElement(first);
        resizeElement(second);

        expect(callback).not.toHaveBeenCalled();
        expect(isObserved(first)).toBe(false);
        expect(isObserved(second)).toBe(false);
    });

    it('Given_a_disconnected_observer_When_it_observes_again_Then_it_is_called_back_once_more', () => {
        // A browser's observer can be reused after disconnect, so the fake must not forget it.
        const { observer, callback } = trackedObserver();
        const element = document.createElement('div');
        observer.observe(element);
        observer.disconnect();

        observer.observe(element);
        resizeElement(element);

        expect(callback).toHaveBeenCalledTimes(1);
    });

    it('Given_jsdom_When_a_test_file_runs_Then_the_global_ResizeObserver_is_the_fake', () => {
        // jsdom has none of its own; the setup file installs this one, so a component that
        // observes its size can render in any jsdom test.
        expect(globalThis.ResizeObserver).toBe(FakeResizeObserver);
    });
});
