/**
 * A transaction as the accounting screens show it: what happened, the entries it posted, and
 * where it has got to in its lifecycle.
 */
import type { Money } from './money';

/**
 * Where a transaction has got to (ADR-028: a discriminated object on the wire).
 *
 * Each state carries exactly what it has: a pending transaction has no date, a cleared or
 * reconciled one has the date it got there, and a voided one also has the reason.
 */
export type TransactionState =
    | { readonly status: 'PENDING' }
    | { readonly status: 'CLEARED'; readonly on: string }
    | { readonly status: 'RECONCILED'; readonly on: string }
    | { readonly status: 'VOIDED'; readonly on: string; readonly reason: string };

/** The four lifecycle statuses. */
export type TransactionStatus = TransactionState['status'];

/** One posting against one account. Signed, because double entry balances (ADR-011). */
export interface Entry {
    /** The id of the account posted to. */
    readonly account: string;
    readonly amount: Money;
}

/** A recorded transaction. Its entries sum to zero; the browser never checks or computes that. */
export interface Transaction {
    readonly id: string;
    /** An ISO `YYYY-MM-DD` date. */
    readonly occurredOn: string;
    readonly description: string;
    readonly entries: readonly Entry[];
    readonly state: TransactionState;
}
