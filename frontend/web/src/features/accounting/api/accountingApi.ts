/**
 * The accounting feature's calls to the ledger, and the web client's anti-corruption edge for them
 * (ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Decimal amounts stay the strings the ledger sent (ADR-028). A type, currency or amount
 * the contract does not allow is refused here with `ApiBodyError`, as planning and portfolio refuse
 * theirs, rather than reaching a screen.
 */
import { ApiBodyError, LEDGER_API, requestJson, sendJson } from '@/shared/api/client';
import type {
    AccountResponse,
    BalanceResponse,
    EntryDto,
    MoneyDto,
    OpenAccountRequest,
    RecordTransactionRequest,
    TransactionResponse,
    TransactionStateResponse,
    TransitionRequest,
    VoidRequest,
} from '@/shared/api/contracts/ledger/types.gen';
import { decimalString } from '@/shared/api/decimal';
import { pathSegment } from '@/shared/api/pathSegment';
import { log } from '@/shared/lib/logger';

import {
    type Account,
    ACCOUNT_TYPES,
    type AccountType,
    type OpenAccountInput,
} from '../types/account';
import { CURRENCIES, type Currency, type Money } from '../types/money';
import type {
    Entry,
    RecordTransactionInput,
    Transaction,
    TransactionState,
} from '../types/transaction';

/** The ledger's code for opening an account under a name already open (ADR-028: 409). */
export const DUPLICATE_ACCOUNT_NAME = 'DUPLICATE_ACCOUNT_NAME';

/** The ledger's code for an account id in the path that names no account (ADR-028: 404). */
export const ACCOUNT_NOT_FOUND = 'ACCOUNT_NOT_FOUND';

/** The ledger's code for a lifecycle action the transaction's state does not admit (409). */
export const ILLEGAL_TRANSITION = 'ILLEGAL_TRANSITION';

const ACCOUNTS_URL = `${LEDGER_API}/accounts`;
const TRANSACTIONS_URL = `${LEDGER_API}/transactions`;

/** The URL of one account's sub-resource, with the id encoded as a single path segment. */
function accountUrl(accountId: string, subResource: string): string {
    return `${ACCOUNTS_URL}/${pathSegment(accountId)}/${subResource}`;
}

/** The URL of one transaction's lifecycle action, with the id encoded as a single path segment. */
function transactionUrl(transactionId: string, action: string): string {
    return `${TRANSACTIONS_URL}/${pathSegment(transactionId)}/${action}`;
}

/**
 * A member whose value the contract does not allow.
 *
 * The generated types promise one of five account types and currencies, but the body is
 * unvalidated JSON. An unknown type would render an account with no type at all, and an unknown
 * currency would throw inside `MoneyText` at render, replacing the whole accounting area rather
 * than failing the one read; an amount is refused the same way by the shared `decimalString`. The
 * value itself is left out of the log line, since it arrived unvalidated.
 *
 * @throws ApiBodyError - always.
 */
function disallowedValue(url: string, member: string): never {
    log.error('ledger response has a value the contract does not allow', { url, member });
    throw new ApiBodyError(url, `A response arrived with a ${member} the contract does not allow`);
}

function knownAccountType(value: AccountType, url: string): AccountType {
    return ACCOUNT_TYPES.includes(value) ? value : disallowedValue(url, 'type');
}

function knownCurrency(value: Currency, url: string): Currency {
    return CURRENCIES.includes(value) ? value : disallowedValue(url, 'currency');
}

function toAccount(wire: AccountResponse, url: string): Account {
    return {
        id: wire.id,
        name: wire.name,
        type: knownAccountType(wire.type, url),
        currency: knownCurrency(wire.currency, url),
    };
}

function toMoney(wire: MoneyDto, url: string): Money {
    return {
        amount: decimalString(wire.amount, url, 'amount'),
        currency: knownCurrency(wire.currency, url),
    };
}

function toEntry(wire: EntryDto, url: string): Entry {
    return { account: wire.account, amount: toMoney(wire.amount, url) };
}

/**
 * A member the contract marks optional but a state's variant always carries.
 *
 * The contract is flat - `on` and `reason` are optional whatever the status - because the document
 * has no discriminated variant to describe (ADR-028), so only this mapper knows which status
 * carries which. A variant without its member is the ledger breaking its own mapping, and there is
 * no honest way to show a cleared transaction with no date, so the read fails here instead.
 *
 * @throws ApiBodyError - when the member is absent.
 */
function variantMember(value: string | undefined, member: string, url: string): string {
    if (value === undefined) {
        log.error('ledger transaction state lacks a member its status carries', { url, member });
        throw new ApiBodyError(url, `A transaction state arrived without its ${member}`);
    }
    return value;
}

/**
 * A state whose status the contract does not name.
 *
 * The generated type lists four statuses, but the body is unvalidated JSON, so the `switch` below
 * keeps a runtime default. Without one, a fifth status would leave the state `undefined` for a
 * screen to crash on. The status itself is left out of the log line, since it arrived unvalidated.
 *
 * @throws ApiBodyError - always.
 */
function unknownStatus(url: string): never {
    log.error('ledger transaction state has a status the contract does not name', { url });
    throw new ApiBodyError(
        url,
        'A transaction state arrived with a status the contract does not name',
    );
}

function toTransactionState(wire: TransactionStateResponse, url: string): TransactionState {
    switch (wire.status) {
        case 'PENDING':
            return { status: 'PENDING' };
        case 'CLEARED':
            return { status: 'CLEARED', on: variantMember(wire.on, 'on', url) };
        case 'RECONCILED':
            return { status: 'RECONCILED', on: variantMember(wire.on, 'on', url) };
        case 'VOIDED':
            return {
                status: 'VOIDED',
                on: variantMember(wire.on, 'on', url),
                reason: variantMember(wire.reason, 'reason', url),
            };
        default:
            return unknownStatus(url);
    }
}

function toTransaction(wire: TransactionResponse, url: string): Transaction {
    return {
        id: wire.id,
        occurredOn: wire.occurredOn,
        description: wire.description,
        entries: wire.entries.map((entry) => toEntry(entry, url)),
        state: toTransactionState(wire.state, url),
    };
}

/**
 * Every account, in the order the ledger lists them, which is by name (ADR-028).
 *
 * @returns the accounts, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body, or an account has a type or
 *   currency the contract does not allow.
 */
export async function listAccounts(): Promise<Account[]> {
    const wire = await requestJson<AccountResponse[]>(ACCOUNTS_URL);
    return wire.map((account) => toAccount(account, ACCOUNTS_URL));
}

/**
 * Open an account. The ledger generates its id.
 *
 * @param input - the name, type and currency to open it with.
 * @returns the account as opened.
 * @throws ApiError - on a non-2xx response: 409 `DUPLICATE_ACCOUNT_NAME` for a name already open,
 *   400 `MALFORMED_REQUEST`, possibly listing field `errors`, for a request the ledger cannot read.
 * @throws ApiBodyError - when the response carries no JSON body, or the account has a type or
 *   currency the contract does not allow.
 */
export async function openAccount(input: OpenAccountInput): Promise<Account> {
    const body: OpenAccountRequest = {
        name: input.name,
        type: input.type,
        currency: input.currency,
    };
    const wire = await sendJson<AccountResponse>(ACCOUNTS_URL, 'POST', body);
    return toAccount(wire, ACCOUNTS_URL);
}

/**
 * An account's balance, derived by the ledger from its entries. The browser never sums one.
 *
 * @param accountId - the account's id.
 * @returns the balance, in the account's currency, exactly as the ledger computed it.
 * @throws ApiError - on a non-2xx response: 404 `ACCOUNT_NOT_FOUND` for an unknown account.
 * @throws ApiBodyError - when the response carries no JSON body, or the balance has an amount or
 *   currency the contract does not allow.
 * @throws RangeError - before anything is sent, when the id is empty, `.` or `..`, which
 *   would name another path (`pathSegment`).
 */
export async function getBalance(accountId: string): Promise<Money> {
    const url = accountUrl(accountId, 'balance');
    const wire = await requestJson<BalanceResponse>(url);
    return toMoney(wire.balance, url);
}

/**
 * The transactions touching one account, in the order the ledger lists them.
 *
 * Every entry of each transaction is kept, whichever account it posts to. Picking out this
 * account's side is the screen's business, and is never a sum.
 *
 * @param accountId - the account's id.
 * @returns the transactions, each state mapped to its variant and every amount as received.
 * @throws ApiError - on a non-2xx response: 404 `ACCOUNT_NOT_FOUND` for an unknown account.
 * @throws ApiBodyError - when the response carries no JSON body, a state lacks a member its status
 *   always carries or has a status the contract does not name, or an entry has an amount or
 *   currency the contract does not allow.
 * @throws RangeError - before anything is sent, when the id is empty, `.` or `..`, which
 *   would name another path (`pathSegment`).
 */
export async function listAccountTransactions(accountId: string): Promise<Transaction[]> {
    const url = accountUrl(accountId, 'transactions');
    const wire = await requestJson<TransactionResponse[]>(url);
    return wire.map((transaction) => toTransaction(transaction, url));
}

/**
 * Record a transaction by hand. The ledger generates its id and checks its entries balance.
 *
 * The browser does no arithmetic and no balance check of its own (ADR-029): an unbalanced set of
 * entries is rejected by the ledger, as 422 `RULE_VIOLATED`, not caught here.
 *
 * @param input - the date, description and entries, each entry's amount already signed by
 *   `entryRowAmount` and its currency the posted-to account's own.
 * @returns the transaction as recorded, in its initial `PENDING` state.
 * @throws ApiError - on a non-2xx response: 422 `RULE_VIOLATED` for entries that do not sum to
 *   zero, 422 `UNKNOWN_ACCOUNT` or `CURRENCY_MISMATCH`, 400 `MALFORMED_REQUEST` possibly listing
 *   field `errors`.
 * @throws ApiBodyError - when the response carries no JSON body, or anything
 *   `listAccountTransactions` refuses.
 */
export async function recordTransaction(input: RecordTransactionInput): Promise<Transaction> {
    const body: RecordTransactionRequest = {
        occurredOn: input.occurredOn,
        description: input.description,
        entries: input.entries.map((entry) => ({
            account: entry.account,
            amount: { amount: entry.amount.amount, currency: entry.amount.currency },
        })),
    };
    const wire = await sendJson<TransactionResponse>(TRANSACTIONS_URL, 'POST', body);
    return toTransaction(wire, TRANSACTIONS_URL);
}

/**
 * Clear a transaction: mark it as having settled, on the given date.
 *
 * @param transactionId - the transaction's id.
 * @param on - the ISO `YYYY-MM-DD` clearing date, never before the transaction occurred.
 * @returns the transaction in its new, `CLEARED` state.
 * @throws ApiError - on a non-2xx response: 404, 409 `ILLEGAL_TRANSITION` when the transaction is
 *   not `PENDING`, 422 for a date before the transaction occurred.
 * @throws ApiBodyError - when the response carries no JSON body.
 * @throws RangeError - before anything is sent, when the id is empty, `.` or `..`, which
 *   would name another path (`pathSegment`).
 */
export async function clearTransaction(transactionId: string, on: string): Promise<Transaction> {
    const url = transactionUrl(transactionId, 'clear');
    const body: TransitionRequest = { on };
    const wire = await sendJson<TransactionResponse>(url, 'POST', body);
    return toTransaction(wire, url);
}

/**
 * Reconcile a transaction: mark it as matched against a statement, on the given date. A
 * reconciled transaction is frozen and admits no further transition.
 *
 * @param transactionId - the transaction's id.
 * @param on - the ISO `YYYY-MM-DD` reconciling date, never before the transaction cleared.
 * @returns the transaction in its new, `RECONCILED` state.
 * @throws ApiError - on a non-2xx response: 404, 409 `ILLEGAL_TRANSITION` when the transaction is
 *   not `CLEARED`, 422 for a date before it cleared.
 * @throws ApiBodyError - when the response carries no JSON body.
 * @throws RangeError - before anything is sent, when the id is empty, `.` or `..`, which
 *   would name another path (`pathSegment`).
 */
export async function reconcileTransaction(
    transactionId: string,
    on: string,
): Promise<Transaction> {
    const url = transactionUrl(transactionId, 'reconcile');
    const body: TransitionRequest = { on };
    const wire = await sendJson<TransactionResponse>(url, 'POST', body);
    return toTransaction(wire, url);
}

/**
 * Void a transaction: mark it as never having counted towards a balance, on the given date, for
 * the given reason. The entries stay on the record; only their effect is withdrawn.
 *
 * @param transactionId - the transaction's id.
 * @param on - the ISO `YYYY-MM-DD` voiding date, never before the transaction occurred.
 * @param reason - why it is void, kept on the record.
 * @returns the transaction in its new, `VOIDED` state.
 * @throws ApiError - on a non-2xx response: 404, 409 `ILLEGAL_TRANSITION` when the transaction is
 *   already `RECONCILED` or `VOIDED`, 422 for a date before it occurred.
 * @throws ApiBodyError - when the response carries no JSON body.
 * @throws RangeError - before anything is sent, when the id is empty, `.` or `..`, which
 *   would name another path (`pathSegment`).
 */
export async function voidTransaction(
    transactionId: string,
    on: string,
    reason: string,
): Promise<Transaction> {
    const url = transactionUrl(transactionId, 'void');
    const body: VoidRequest = { on, reason };
    const wire = await sendJson<TransactionResponse>(url, 'POST', body);
    return toTransaction(wire, url);
}
