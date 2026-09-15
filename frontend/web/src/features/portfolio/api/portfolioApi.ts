/**
 * The portfolio feature's calls to the assistant, and the web client's anti-corruption edge for
 * them (ADR-026, ADR-029).
 *
 * Each function types the response with the generated wire type and maps it, member by member,
 * onto the feature's own type. A generated type stops here: components and hooks see only
 * `types/`. Quantities and amounts stay the strings the assistant sent, signs included - never
 * converted (ADR-028, ADR-029).
 */
import { ApiBodyError, ASSISTANT_API, requestJson } from '@/shared/api/client';
import type {
    MoneyModel,
    PositionModel,
    ValuationModel,
} from '@/shared/api/contracts/assistant/types.gen';
import { log } from '@/shared/lib/logger';

import type { Position, Valuation } from '../types/holding';
import { CURRENCIES, type Money } from '../types/money';

/** The assistant's code for a symbol under which nothing is held (ADR-028: 404). */
export const HOLDING_NOT_FOUND = 'HOLDING_NOT_FOUND';

/** The assistant's code for a holding no recent enough quote can value (ADR-028: 503). */
export const PRICE_UNAVAILABLE = 'PRICE_UNAVAILABLE';

const HOLDINGS_URL = `${ASSISTANT_API}/portfolio/holdings`;

/**
 * The URL of one read about a holding, its symbol encoded as a single path segment: a symbol
 * containing a space or a slash would otherwise change the path it names.
 */
function holdingUrl(symbol: string, read: 'position' | 'valuation'): string {
    return `${HOLDINGS_URL}/${encodeURIComponent(symbol)}/${read}`;
}

/**
 * A currency the contract does not name.
 *
 * The generated type promises one of five codes, but the body is unvalidated JSON, so this keeps
 * a runtime default rather than trusting it blindly.
 *
 * @throws ApiBodyError - always.
 */
function unknownCurrency(url: string): never {
    log.error('assistant response has a currency the contract does not name', { url });
    throw new ApiBodyError(url, 'A response arrived with a currency the contract does not name');
}

function toMoney(wire: MoneyModel, url: string): Money {
    if (!CURRENCIES.includes(wire.currency)) {
        return unknownCurrency(url);
    }
    return { amount: wire.amount, currency: wire.currency };
}

function toPosition(wire: PositionModel, url: string): Position {
    return { symbol: wire.symbol, quantity: wire.quantity, cost: toMoney(wire.cost, url) };
}

function toValuation(wire: ValuationModel, url: string): Valuation {
    return {
        symbol: wire.symbol,
        quantity: wire.quantity,
        cost: toMoney(wire.cost, url),
        unitPrice: toMoney(wire.unitPrice, url),
        marketValue: toMoney(wire.marketValue, url),
        unrealisedGain: toMoney(wire.unrealisedGain, url),
    };
}

/**
 * What is held under one symbol, across its lots, and what it cost in total.
 *
 * @param symbol - the symbol as the owner typed it; the assistant upper-cases it.
 * @returns the position, its quantity and cost exactly as the assistant sent them.
 * @throws ApiError - on a non-2xx response: 404 `HOLDING_NOT_FOUND` when nothing is held.
 * @throws ApiBodyError - when the response carries no JSON body, or a currency the contract does
 *   not name.
 */
export async function readPosition(symbol: string): Promise<Position> {
    const url = holdingUrl(symbol, 'position');
    const wire = await requestJson<PositionModel>(url);
    return toPosition(wire, url);
}

/**
 * One holding valued at the latest usable unit price, and the gain that price implies.
 *
 * @param symbol - the symbol as the owner typed it; the assistant upper-cases it.
 * @returns the valuation, every figure exactly as the assistant sent it - an unrealised loss as a
 *   negative amount.
 * @throws ApiError - on a non-2xx response: 404 `HOLDING_NOT_FOUND` when nothing is held, 503
 *   `PRICE_UNAVAILABLE` when no quote is recent enough to value it.
 * @throws ApiBodyError - when the response carries no JSON body, or a currency the contract does
 *   not name.
 */
export async function valueHolding(symbol: string): Promise<Valuation> {
    const url = holdingUrl(symbol, 'valuation');
    const wire = await requestJson<ValuationModel>(url);
    return toValuation(wire, url);
}
