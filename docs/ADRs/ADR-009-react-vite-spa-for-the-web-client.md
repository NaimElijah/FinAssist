# ADR-009: A React and Vite single-page application for the web client

- **Status:** Accepted
- **Date:** 2026-08-29

## Context

FinAssist had no user interface. The three capability areas in
[requirements](../requirements/requirements.md) — tracking, assistance and analysis — all
imply one, and a web client is now in scope.

The system has a single owner and no public surface. There is no SEO requirement, no
first-paint budget driven by anonymous traffic, and no content to render on a server. What it
does have is a lot of interactive state: filters, a conversation, charts over a portfolio.

Node 24.16.0 and npm 11.13.0 are installed on the development machine. pnpm and yarn are not.

## Decision

`frontend/web` is a React 19 single-page application built by Vite 8, in TypeScript, tested
with Vitest, linted by ESLint with type-aware rules, formatted by Prettier, and managed with
npm and a committed `package-lock.json`.

`src/features` holds one directory per bounded context, named with the same words the backend
uses ([ADR-010](ADR-010-bounded-context-map.md)). A feature may not import another feature;
composition happens in `src/app`, and `tests/architecture.test.ts` fails the build otherwise.

**TypeScript is pinned to 6.0.3 rather than the current 7.0.2**, because `typescript-eslint`
8.68.0 declares `typescript: >=4.8.4 <6.1.0`. Type-aware linting is worth more on a codebase
this young than a newer compiler is; the pin is recorded with its reason in
`eslint.config.js` and lifts as soon as `typescript-eslint` supports 7.

## Consequences

**Makes easy**

- A static bundle: no Node process to run, monitor or patch in production.
- Vite's dev server and Vitest share one configuration and one transform pipeline.
- The feature-per-context layout keeps one term meaning one thing from the UI to the
  aggregate, which is what makes a ubiquitous language worth having.
- ESLint's type-aware rules already earned their place: they caught a `HeadersInit` spread bug
  in the API client before any code used it.

**Makes hard**

- No server-side rendering. If the client ever needs SEO or a meaningful first paint over a
  slow link, that is a migration, not a configuration change.
- npm's resolution is slower than pnpm's and its `node_modules` larger. Accepted, because
  adding a package manager to a repository that already carries Maven and uv needs a stronger
  reason than speed.
- Being one minor behind on TypeScript until the linter catches up.
- `marketdata` has no feature directory, so the mapping between contexts and features is
  four-of-five rather than one-to-one. It has no user-facing surface and reaches the UI
  through `portfolio`.

## Alternatives considered

- **Next.js with the App Router** — SSR and React Server Components, and file-based routing.
  Rejected: it adds a Node server to operate for a single-owner tool, and its framework-owned
  `app/` directory splits routing away from feature code, weakening exactly the
  context-mirroring this decision is for. Revisit if a public, unauthenticated surface appears.
- **A server-rendered UI from the ledger (Thymeleaf or similar)** — no second toolchain at
  all, and tempting given the ledger already runs Spring Boot. Rejected because it would put
  presentation inside the service that owns the money invariants, and because the assistant,
  not the ledger, is the service a person talks to.
- **pnpm or yarn** — better workspace support and faster installs. Rejected: neither is
  installed, there is one frontend package rather than a workspace, and npm ships with Node.
