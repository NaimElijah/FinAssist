/**
 * Generates TypeScript types from the ledger's, the assistant's and the gateway's committed
 * OpenAPI documents (ADR-029).
 *
 * Types only: `plugins` names `@hey-api/typescript` alone, so no SDK and no HTTP client are
 * generated. `src/shared/api/client.ts` stays the one place the app talks HTTP, and a
 * feature's own `api/` module is what narrows a generated type into something the feature
 * uses (ADR-029's anti-corruption edge). `output.entryFile: false` skips the barrel file
 * hey-api would otherwise generate, since nothing here imports these types by folder.
 *
 * Paths are relative to this file's directory (`frontend/web`, where `generate:api` runs):
 * `input` reaches up to the repository-root `contracts/`, and `output` writes back under
 * `src/shared/api/contracts/`, committed so a contract change shows up as a diff rather than
 * a silent drift (ADR-028's drift check, mirrored here for the client).
 */
import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig([
    {
        input: '../../contracts/ledger-v1.openapi.json',
        output: {
            path: 'src/shared/api/contracts/ledger',
            entryFile: false,
        },
        plugins: ['@hey-api/typescript'],
    },
    {
        input: '../../contracts/assistant-v1.openapi.json',
        output: {
            path: 'src/shared/api/contracts/assistant',
            entryFile: false,
        },
        plugins: ['@hey-api/typescript'],
    },
    {
        input: '../../contracts/gateway-v1.openapi.json',
        output: {
            path: 'src/shared/api/contracts/gateway',
            entryFile: false,
        },
        plugins: ['@hey-api/typescript'],
    },
]);
