/**
 * A stand-in for the browser's `ResizeObserver`, which jsdom does not implement, so a component
 * that watches its own size can render under test, and a test can tell it a size changed.
 *
 * jsdom lays nothing out, so nothing here ever resizes by itself: an observer is called back only
 * when a test calls `resizeElement`, which keeps every test deterministic. Unlike a browser, it
 * makes no initial call when an element is first observed; a component measures once itself.
 */

/** One connected observer's callback and the elements it watches. */
interface Watch {
    readonly callback: ResizeObserverCallback;
    readonly targets: Set<Element>;
}

/** Every observer watching at least one element since it last disconnected. */
const watches = new Map<FakeResizeObserver, Watch>();

/**
 * `ResizeObserver` for jsdom. `src/shared/testing/browserGlobals.ts` installs it as the global
 * one in every jsdom test file.
 */
export class FakeResizeObserver implements ResizeObserver {
    readonly #callback: ResizeObserverCallback;

    /** @param callback - called by `resizeElement` for each element this observer watches. */
    constructor(callback: ResizeObserverCallback) {
        this.#callback = callback;
    }

    /** Watch `target`; observing an element already watched changes nothing. */
    observe(target: Element): void {
        const watch = watches.get(this) ?? { callback: this.#callback, targets: new Set() };
        watch.targets.add(target);
        watches.set(this, watch);
    }

    /** Stop watching `target`. */
    unobserve(target: Element): void {
        watches.get(this)?.targets.delete(target);
    }

    /** Stop watching every element. The observer can observe again afterwards, as in a browser. */
    disconnect(): void {
        watches.delete(this);
    }
}

/** `target`'s entry, sized from its bounding box, as a browser would report it. */
function entryFor(target: Element): ResizeObserverEntry {
    const contentRect = target.getBoundingClientRect();
    const size: ResizeObserverSize = {
        inlineSize: contentRect.width,
        blockSize: contentRect.height,
    };
    return {
        target,
        contentRect,
        borderBoxSize: [size],
        contentBoxSize: [size],
        devicePixelContentBoxSize: [size],
    };
}

/**
 * Report that `target` changed size: call back every observer watching it, with its entry, as a
 * browser does after a layout that resized it. Wrap the call in `act` when a component's state
 * changes in response.
 *
 * @param target - the element whose size changed.
 */
export function resizeElement(target: Element): void {
    const entry = entryFor(target);
    for (const [observer, { callback, targets }] of [...watches]) {
        if (targets.has(target)) {
            callback([entry], observer);
        }
    }
}

/**
 * Whether any observer still watches `target`, so a test can prove a component stops observing
 * once it unmounts.
 *
 * @param target - the element to look for.
 */
export function isObserved(target: Element): boolean {
    return [...watches.values()].some(({ targets }) => targets.has(target));
}
