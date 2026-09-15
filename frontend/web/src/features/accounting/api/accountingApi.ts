/**
 * The accounting feature's calls to the ledger, and the web client's anti-corruption edge for them
 * (ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Decimal amounts stay the strings the ledger sent (ADR-028).
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
} from '@/shared/api/contracts/ledger/types.gen';
import { log } from '@/shared/lib/logger';

import type { Account, OpenAccountInput } from '../types/account';
import type { Money } from '../types/money';
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

const ACCOUNTS_URL = `${LEDGER_API}/accounts`;
const TRANSACTIONS_URL = `${LEDGER_API}/transactions`;

/** The URL of one account's sub-resource, with the id encoded as a single path segment. */
function accountUrl(accountId: string, subResource: string): string {
    return `${ACCOUNTS_URL}/${encodeURIComponent(accountId)}/${subResource}`;
}

function toAccount(wire: AccountResponse): Account {
    return { id: wire.id, name: wire.name, type: wire.type, currency: wire.currency };
}

function toMoney(wire: MoneyDto): Money {
    return { amount: wire.amount, currency: wire.currency };
}

function toEntry(wire: EntryDto): Entry {
    return { account: wire.account, amount: toMoney(wire.amount) };
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
        entries: wire.entries.map(toEntry),
        state: toTransactionState(wire.state, url),
    };
}

/**
 * Every account, in the order the ledger lists them, which is by name (ADR-028).
 *
 * @returns the accounts, in the ledger's order.
 * @throws ApiError - on a non-2xx response.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function listAccounts(): Promise<Account[]> {
    const wire = await requestJson<AccountResponse[]>(ACCOUNTS_URL);
    return wire.map(toAccount);
}

/**
 * Open an account. The ledger generates its id.
 *
 * @param input - the name, type and currency to open it with.
 * @returns the account as opened.
 * @throws ApiError - on a non-2xx response: 409 `DUPLICATE_ACCOUNT_NAME` for a name already open,
 *   400 `MALFORMED_REQUEST`, possibly listing field `errors`, for a request the ledger cannot read.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function openAccount(input: OpenAccountInput): Promise<Account> {
    const body: OpenAccountRequest = {
        name: input.name,
        type: input.type,
        currency: input.currency,
    };
    const wire = await sendJson<AccountResponse>(ACCOUNTS_URL, 'POST', body);
    return toAccount(wire);
}

/**
 * An account's balance, derived by the ledger from its entries. The browser never sums one.
 *
 * @param accountId - the account's id.
 * @returns the balance, in the account's currency, exactly as the ledger computed it.
 * @throws ApiError - on a non-2xx response: 404 `ACCOUNT_NOT_FOUND` for an unknown account.
 * @throws ApiBodyError - when the response carries no JSON body.
 */
export async function getBalance(accountId: string): Promise<Money> {
    const wire = await requestJson<BalanceResponse>(accountUrl(accountId, 'balance'));
    return toMoney(wire.balance);
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
 * @throws ApiBodyError - when the response carries no JSON body, or a state lacks a member its
 *   status always carries, or has a status the contract does not name.
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
 * @throws ApiBodyError - when the response carries no JSON body.
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
