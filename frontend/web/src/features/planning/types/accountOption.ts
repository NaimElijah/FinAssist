/**
 * The accounts planning can offer as a projection's opening position, in planning's own terms.
 *
 * Accounts belong to accounting, and a feature imports no other feature (ADR-010). So planning
 * declares here what it needs, and `src/app` adapts accounting's accounts into it - the web
 * client's counterpart of the ledger's `OpeningPositionProvider` and `AccountRef` (ADR-022).
 */

/** One account planning can offer. */
export interface AccountOption {
    /** The opaque reference the ledger's projection query takes as `cash` or `invested`. */
    readonly ref: string;
    /** The words shown beside the account's checkbox. */
    readonly label: string;
    /** The account's ISO 4217 currency; a projection offers only accounts in its own (ADR-012). */
    readonly currency: string;
}

/** A failed read of the accounts, and a way to try it again. */
export interface AccountOptionsFailure {
    /** Whatever the read threw; `ErrorState` decides what of it to show. */
    readonly error: unknown;
    /** Whether a retry is in flight. */
    readonly retrying: boolean;
    readonly retry: () => void;
}

/**
 * The account choices as `src/app` provides them.
 *
 * Planning must tell three things apart - still loading, failed with nothing to offer, and loaded -
 * so the pickers can say which. A loaded list keeps a later refetch's failure beside it rather
 * than replacing it, the rule every list on these screens keeps.
 */
export type AccountOptions =
    | { readonly status: 'loading' }
    | { readonly status: 'failed'; readonly failure: AccountOptionsFailure }
    | {
          readonly status: 'loaded';
          readonly options: readonly AccountOption[];
          /** A later refetch's failure, shown beside the options it failed to refresh. */
          readonly failure: AccountOptionsFailure | null;
      };

/**
 * The accounts that can be offered now: every loaded one, or none while loading or failed - a
 * projection may still be asked for, opening both lines at zero.
 */
export function offeredAccounts(accountOptions: AccountOptions): readonly AccountOption[] {
    return accountOptions.status === 'loaded' ? accountOptions.options : [];
}
