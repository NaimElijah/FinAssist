// @vitest-environment jsdom
// Renders a hook through React, so it needs a DOM.

import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AccountOptions } from '../types/accountOption';
import { AccountOptionsContext, useAccountOptions } from './accountOptions';

afterEach(() => {
    // In afterEach, so a failing assertion cannot leave console.error silenced for later tests.
    vi.restoreAllMocks();
});

describe('useAccountOptions', () => {
    it('Given_no_provider_When_called_Then_it_throws_naming_the_missing_provider', () => {
        // React logs the render error itself, which is noise here and not what is under test.
        vi.spyOn(console, 'error').mockImplementation(() => undefined);

        expect(() => renderHook(() => useAccountOptions())).toThrow(/AccountOptionsContext/);
    });

    it('Given_a_provider_When_called_Then_it_returns_the_value_provided', () => {
        const provided: AccountOptions = {
            status: 'loaded',
            options: [{ ref: 'acc-1', label: 'Checking', currency: 'ILS' }],
            failure: null,
        };
        function Provider({ children }: { readonly children: ReactNode }) {
            return <AccountOptionsContext value={provided}>{children}</AccountOptionsContext>;
        }

        const { result } = renderHook(() => useAccountOptions(), { wrapper: Provider });

        expect(result.current).toBe(provided);
    });
});
