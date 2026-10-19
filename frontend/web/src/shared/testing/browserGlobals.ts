/**
 * Browser APIs jsdom lacks, installed before every test file runs (`test.setupFiles` in
 * `vite.config.ts`). Only a jsdom file has a `window`; a file under the node environment is left
 * alone.
 *
 * Assigned directly rather than through `vi.stubGlobal`: `fakeFetch` calls `vi.unstubAllGlobals()`
 * when each test finishes, which would remove a stubbed observer from every later test in the file.
 */
import { FakeResizeObserver } from './fakeResizeObserver';

if (typeof window !== 'undefined' && !('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = FakeResizeObserver;
}
