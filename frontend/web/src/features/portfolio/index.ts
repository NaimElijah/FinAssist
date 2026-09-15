/**
 * Portfolio feature - one holding at a time: its position, its valuation, and a disposal priced
 * without being recorded.
 *
 * Mirrors the `portfolio` bounded context in the Python assistant, the one area the assistant
 * serves rather than the ledger (ADR-026). Everything this feature exposes to the rest of the app
 * is re-exported here; nothing outside reaches past this file, and this feature imports no other
 * feature.
 */
export { portfolioRoutes } from './routes';
