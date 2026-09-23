// @vitest-environment jsdom
// Renders a hook through React, so it needs a DOM.

import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useWriteAccess, type WriteAccess, WriteAccessContext } from './writeAccess';

afterEach(() => {
    // In afterEach, so a failing assertion cannot leave console.error silenced for later tests.
    vi.restoreAllMocks();
});

describe('useWriteAccess', () => {
    it('Given_no_provider_When_called_Then_it_throws_naming_the_missing_provider', () => {
        // React logs the render error itself, which is noise here and not what is under test.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderHook(() => useWriteAccess())).toThrow(/WriteAccessContext/);
    });

    it('Given_a_provider_When_called_Then_it_returns_the_access_provided', () => {
        const provided: WriteAccess = { canWrite: false };
        function Provider({ children }: { readonly children: ReactNode }) {
            return <WriteAccessContext value={provided}>{children}</WriteAccessContext>;
        }

        const { result } = renderHook(() => useWriteAccess(), { wrapper: Provider });

        expect(result.current).toBe(provided);
    });
});
