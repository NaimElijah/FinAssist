# ADR-029: The web client's routing, server state, contract types and styling

- **Status:** Accepted, extended by [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)
  and [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-09-14

Extends [ADR-009](ADR-009-react-vite-spa-for-the-web-client.md), which chose a React 19 and Vite 8
single-page application in TypeScript, with one feature directory per bounded context. That decision
stands. This record adds what a client with screens needs on top of it, and its structure section
extends the context-first packaging of [ADR-010](ADR-010-bounded-context-map.md) on the web side.

## Context

ADR-009 was written for a client with no screens, and answered what such a client needs: a framework,
a bundler, a test runner and a layout. The web client still renders a placeholder behind an error
boundary, over one tested HTTP wrapper in `src/shared/api/client.ts`.

[ADR-026](ADR-026-web-client-calls-both-services-through-one-origin.md) puts screens for accounts and
transactions, budgets, planning and portfolio in scope — roughly a dozen views, over two services —
and [ADR-028](ADR-028-http-wire-conventions.md) fixes a wire of decimal strings, discriminated
objects, problem responses and committed OpenAPI documents. Routing, server state, contract types,
styling, charts and interaction tests each need an answer before the first screen, or the first
screen's answer becomes the precedent by accident.

**The bar for a dependency is fixed, and every choice below is held to it:** what it replaces,
whether it is maintained, its licence, and why the platform or an existing dependency is not enough.

**One constraint rules options out before they are weighed.** TypeScript is pinned to 6.0.3 because
`typescript-eslint` 8.68.0 declares `typescript: >=4.8.4 <6.1.0` (ADR-009). A package whose peer range
stops at TypeScript 5 fails `npm ci` with the same ERESOLVE that caused the pin.

**Money has a price in a third language.** `Money` exists once in Java and once in Python, and
[ADR-018](ADR-018-cross-language-money-vectors.md) holds both to every vector in
`contracts/money-vectors.v1.json`. A TypeScript `Money` would owe that file the same.

Today the client depends on `react` and `react-dom` 19.2.8 at runtime, and builds and tests with Vite
8.2.2, Vitest 4.1.11, jsdom, and Testing Library's `react` and `dom` packages.

## Decision

| Need | Choice |
|---|---|
| Routing | `react-router` 8.3.1, in data mode |
| Server state | `@tanstack/react-query` 5.102.8 |
| Contract types | `@hey-api/openapi-ts` 0.99.0, development only, emitting types and no client |
| Styling | CSS Modules, built into Vite, plus CSS custom-property design tokens in `src/shared/ui/` |
| Charts | A hand-rolled SVG line chart with a data-table fallback |
| Interaction tests | `@testing-library/user-event` 14.6.7, development only |
| API mocking | The existing `vi.stubGlobal('fetch')` pattern |
| Global state | None beyond TanStack Query's cache and component state |

**Every new dependency is pinned to an exact version**, as the rest of `package.json` is.

### Each choice, against the bar

**`react-router` 8.3.1 — routing.** MIT. It replaces hand-rolled view state, which would have to
rebuild URLs, history and the back button across roughly a dozen views. It is used in data mode:
`createBrowserRouter` from `react-router`, and `RouterProvider` from `react-router/dom`. 8.3.1 is the
current release, published 2026-08-28. Its peers are `react` and `react-dom` 19.2.7 or later, and
19.2.8 is pinned; its Node engine, 22.22.0 or later, admits every version the client's own
`engines` range allows. It is ESM-only, which Vite consumes natively, and its own tooling is on
TypeScript 6. The v8 line's floor of Vite 7
belongs to `@react-router/dev`, framework mode's tooling, which data mode does not install — and
8.2.2 would clear it regardless. The platform's History API changes a URL; it does not map one to a
view, load that view's data or answer a route that does not exist.

**`@tanstack/react-query` 5.102.8 — server state.** MIT. It replaces fetch hooks written per feature,
which get request races, caching, and refreshing a list after a mutation wrong — and every screen
here does all three. 5.102.8 is the current release, published 2026-08-27, and it peers on React 18
or 19. Neither React nor the existing HTTP wrapper caches, deduplicates or invalidates anything.

**`@hey-api/openapi-ts` 0.99.0 — contract types, development only.** MIT. It replaces hand-written
TypeScript copies of the two contracts. It runs with only the `@hey-api/typescript` plugin and
`output.entryFile: false`, so it emits types and no client, and the existing wrapper stays the
transport. The output is committed, and CI regenerates it and fails on a diff: the web client's half
of ADR-028's drift check. Its peer range admits TypeScript 6. 0.99.0 is the current release, published
2026-06-22. It is pre-1.0, where a minor release may break, so it is pinned exactly and grouped in
Dependabot. Nothing installed reads an OpenAPI document.

**CSS Modules and custom-property tokens — styling.** No dependency: Vite compiles `*.module.css`
itself, and custom properties are the platform's. Design tokens are custom properties in
`src/shared/ui/`. `tsconfig.json`'s `types` gains `vite/client`, so a module import type-checks.

**A hand-rolled SVG line chart — charts.** No dependency. The planning projection and the scenario
comparison are two line series, drawn in SVG, with a data table as the fallback.

**`@testing-library/user-event` 14.6.7 — interaction tests, development only.** MIT. It replaces
`fireEvent`, which `@testing-library/react` already provides and which dispatches one event at a time
without modelling typing or focus. 14.6.7 is the current release, published 2026-09-02, and its peer,
`@testing-library/dom` 7.21.4 or later, is already installed at 10.4.1.

**API mocking stays `vi.stubGlobal('fetch')`.** No dependency. `client.test.ts` already stubs `fetch`,
and stubbing there exercises each feature's real `api/` module, its mapping included.

**No global state library.** TanStack Query's cache holds server state and component state holds the
rest. No client state is shared across screens, so Redux or Zustand would have nothing to hold.

### No money arithmetic in the browser

- **There is no TypeScript `Money`.** A third implementation would owe ADR-018 every vector in
  `contracts/money-vectors.v1.json`, so the browser does no arithmetic on money at all.
- **Amounts stay strings** from the wire to the screen, and are formatted with `Intl.NumberFormat`.
  Its overload that accepts a string is typed only in `lib.es2023.intl`, so `tsconfig.json`'s `lib`
  gains `ES2023.Intl`.
- **The record-transaction form defaults to two entries** and mirrors the amount into the opposite
  column by negating the string's sign, never by parsing it as a number. A split that does not balance
  is refused by the server, and the form shows the problem's message.

### Structure

This extends ADR-009's feature layout and ADR-010's context-first packaging.

- **`src/shared/api/contracts/{ledger,assistant}/`** holds the generated types.
- **Each feature's `api/` maps wire types to the feature's own types.** It is the web client's
  anti-corruption edge: a generated type stops there, as a ledger DTO stops at the assistant's
  outbound adapter.
- **Cross-feature data flows through `src/app`, never through an import**, mirroring the ledger's
  `composition` translations ([ADR-022](ADR-022-ledger-composition-root.md)):
  - `planning` exports `AccountOptionsContext`, a React context, and the `AccountOption`,
    `AccountOptions` and `AccountOptionsFailure` types its value is built from;
  - an app-level bridge component calls `accounting`'s accounts hook and renders `planning`'s
    context, as the provider, with the result.

  Passing the hook to `planning` as a prop instead would break the Rules of Hooks, which forbid
  passing a hook around as a value.
- **`src/features/planning/` is added.** Five of the six contexts then have a feature directory;
  `marketdata` still has no surface of its own and reaches the screen through portfolio figures.
- **Two architecture rules are added by the phases that build the screens**, each proven to fail
  against a deliberate violation before it is trusted: `src/app` imports a feature only through its
  `index.ts`, and no feature imports `src/app`.

## Consequences

**Makes easy**

- URLs, the back button and deep links work across every screen without code written for each.
- A mutation refreshes what it affects by invalidating a query, rather than by every screen
  remembering to refetch.
- A contract change reaches the client as a type error, and CI refuses a client whose committed types
  disagree with the committed documents.
- The browser never computes money, so it owes ADR-018 nothing and cannot disagree with a service
  about a sum.

**Makes hard**

- Four new packages to keep current — two shipped to the browser, two used only in development — and
  one of them is pre-1.0.
- Every feature writes its own mapping from wire types, so a contract change is edited once per
  feature that reads the shape.
- A hand-rolled chart is code this repository owns: its scales, axes and the shortfall marker are
  tested here, not by a library's other users.
- Cross-feature data needs a bridge in `src/app` each time, which is more ceremony than an import, and
  deliberately so.
- A figure that looks like a sum — a total row, a running balance — has to come from a service,
  because the browser will not compute one.

## Alternatives considered

**Hand-rolled view state instead of a router.** No dependency. Rejected: it loses URLs and the back
button across roughly a dozen views, and deep links would be rebuilt by hand.

**Hand-rolled fetch hooks instead of TanStack Query.** No dependency, and a few lines per hook.
Rejected: they get races, caching and post-mutation refresh wrong, and would get them wrong
differently in each feature.

**`openapi-typescript` 7.13.0 for contract types.** Types only, which is exactly the need. Rejected:
it declares a peer of `typescript ^5.x`, which fails `npm ci` under the TypeScript 6.0.3 pin — the
same ERESOLVE that caused the pin.

**`orval` 8.33.0 for contract types.** Rejected: it is a client generator. It depends on a dozen
framework and client generator packages — Angular, Hono, SWR and Zod among them — plus `typedoc` and
two `typedoc` plugins, for a need that is types alone.

**Hand-written contract types.** No generator at all. Rejected: they drift silently, which is the
failure ADR-028 exists to prevent on the server side.

**Tailwind, or CSS-in-JS.** Rejected: a dependency with no need behind it, when Vite already compiles
CSS Modules and the platform already has custom properties.

**A chart library.** Rejected: a dependency for two line series.

**`fireEvent` alone for interaction tests.** No new dependency. Rejected: it does not model typing and
focus, which is most of what a form does.

**msw for API mocking.** Rejected: an extra dependency for what stubbing at `fetch` already does.

**Redux, or Zustand.** Rejected: no cross-screen client state exists for either to hold.

## Revisit when

- **The UI becomes read-only.** TanStack Query's mutation handling is then more than the need.
- **A third chart type is needed, or interactive zoom or tooltips.** A chart library starts to earn
  its place.
- **`@hey-api/openapi-ts` reaches 1.0.** The exact pin and the Dependabot group exist because it has
  not.
- **State appears that two screens share and no service holds.** A client state library then has
  something to hold.
- **A screen needs arithmetic on money that no service provides.** The first answer is an endpoint;
  only if that cannot be is it a TypeScript `Money` held to ADR-018's vectors.
