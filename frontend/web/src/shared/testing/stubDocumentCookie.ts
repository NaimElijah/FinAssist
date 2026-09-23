/**
 * A `document.cookie` stand-in for a test running under `@vitest-environment node`.
 *
 * `sendJson` (`src/shared/api/client.ts`) reads `document.cookie` unconditionally to build the
 * CSRF header, but Node has no `document` global at all - not even one with an empty
 * `cookie` - so a suite exercising `sendJson` under Node must stub the whole object, not just
 * the property.
 */
import { vi } from 'vitest';

/**
 * Stub `document` to one carrying only `cookie`, for the rest of the current test.
 *
 * `vi.stubGlobal` sets it on `globalThis`, which is enough for a bare `document` reference to
 * resolve at runtime even under Node, where nothing declared it lexically. Every caller already
 * tears its globals down with its own `afterEach(() => vi.unstubAllGlobals())` - the same one
 * that restores `fetch` - so this helper registers no teardown of its own.
 *
 * @param cookie - the raw cookie string `sendJson` will read as `document.cookie`.
 */
export function stubDocumentCookie(cookie: string): void {
    vi.stubGlobal('document', { cookie });
}
