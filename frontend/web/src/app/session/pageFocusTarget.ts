/**
 * How the app's frame tells `SessionGate` where focus belongs on the page it shows.
 *
 * A retried session check that succeeds replaces the outage screen, and the retry button with it,
 * by the whole app. `SessionGate` moves focus to what came back (`useRetryFocus`), but the page
 * renders inside the router, out of its reach; `Layout` names the page's heading through this
 * context instead, the same target a route change focuses.
 */
import { createContext } from 'react';

/** Names the element a restored session focuses, or `null` once the page that named it is gone. */
export type NamePageFocusTarget = (target: HTMLElement | null) => void;

/**
 * Provided by `SessionGate`. The default accepts a name and ignores it, so a frame rendered
 * outside the gate, such as a screen test mounting its routes alone, runs unchanged.
 */
export const PageFocusTargetContext = createContext<NamePageFocusTarget>(() => undefined);
