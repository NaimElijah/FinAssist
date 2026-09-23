// @vitest-environment jsdom
// Renders a hook through React, so it needs a DOM.

import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { Session } from './sessionApi';
import { SessionContext, useSignedInSession } from './sessionContext';

afterEach(() => {
    // In afterEach, so a failing assertion cannot leave console.error silenced for later tests.
    vi.restoreAllMocks();
});

describe('useSignedInSession', () => {
    it('Given_no_provider_When_called_Then_it_throws_naming_the_missing_provider', () => {
        // React logs the render error itself, which is noise here and not what is under test.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderHook(() => useSignedInSession())).toThrow(/SessionContext/);
    });

    it('Given_a_provider_When_called_Then_it_returns_the_session_provided', () => {
        const provided: Session = { subject: 'sub-1', name: 'Naim', roles: ['owner'] };
        function Provider({ children }: { readonly children: ReactNode }) {
            return <SessionContext value={provided}>{children}</SessionContext>;
        }

        const { result } = renderHook(() => useSignedInSession(), { wrapper: Provider });

        expect(result.current).toBe(provided);
    });
});
