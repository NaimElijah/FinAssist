# CLAUDE.md — web client

React 19, Vite 8, TypeScript. The root `.claude/CLAUDE.md` binds here too; this file adds what
binds the web client alone.

## Layout

`src/features/` holds one directory per user-facing context; `src/shared/` is their common ground;
`src/app/` composes them, and `src/app/session/` gates every screen on a signed-in session
(`SessionGate`, `SessionContext`). `vite.config.ts` proxies `/ledger`, `/assistant` and `/bff` to
the gateway. Each feature maps the generated wire types onto its own types in its `api/`.

- **Accounting.** `AccountsPage` opens an account and lists every one with its derived balance.
  `RecordTransactionPage` records a transaction by hand at the static route
  `/accounts/transactions/new`. `AccountDetailPage` shows one account's transactions and moves each
  through `Clear`, `Reconcile` or `Void`, offering only the transitions its status legally admits.
- **Budgeting.** `BudgetsPage` lists every budget and defines one for a calendar month with a
  limit per category. `BudgetReviewPage` (`/budgets/:budgetId`) shows spend by category —
  overspent, unbudgeted and uncategorised — taking the budget's own period and currency from the
  cached list rather than a single-item read. `CategorisationRulesPage` (`/budgets/rules`) lists
  rules in the order they are tried and adds another. `/budgets/rules` outranks the dynamic
  `/budgets/:budgetId` at the same depth by React Router's own static-over-dynamic ranking.
- **Planning.** `PlannedFlowsPage` plans flows and moves each through its lifecycle.
  `ScenariosPage` defines scenarios. `ProjectionPage` (`/planning/projection`) charts a scenario's
  projection, with its first shortfall, against the cash and invested accounts the owner chooses.
  `ComparisonPage` (`/planning/compare`) compares two or more scenarios against a baseline in
  tables. Planning's account choices come from accounting through `PlanningAccountsBridge` in
  `src/app`, the web client's one cross-feature translation.
- **Portfolio** (ADR-026, extended by ADR-030). `PortfolioPage` looks a holding up by symbol.
  `HoldingPage` (`/portfolio/:symbol`) shows its position and valuation — nothing held and no
  usable price are calm empty states rather than errors — and prices a disposal as a calculation
  that records nothing.
- "Ask a question" is deliberately left out; `src/features/assistance/` is an empty placeholder
  for it.

## Commands

From `frontend/web`:

- **Run** — `npm run dev`. **Build** — `make build-web`: `npm run build`.
- **Test** — `make test-web`: `npm run test:coverage`. One test: `npx vitest run -t <name>`.
- **Lint** — `npm run lint`, `npm run format:check`, `npm run typecheck`. **Format** —
  `npm run format`. **Audit** — `npm audit --audit-level=high`. `format:check` runs
  `prettier --check .`, which covers this file too.
- **Regenerate the contract types** — `make generate-web-api`: `npm run generate:api`.

## Build-enforced rules

- **The contract types under `src/shared/api/contracts/` are generated, never hand-written.**
  Regenerate them after any of the three contracts changes — `openapi-ts.config.ts` reads the
  ledger's, the assistant's and the gateway's. CI fails the `web` job on a diff it did not expect
  (ADR-029).
- **TypeScript is pinned to 6.0.3, not 7.x,** because `typescript-eslint` 8 declares
  `typescript: >=4.8.4 <6.1.0` — past that, `npm ci` fails with ERESOLVE. Dependabot is configured
  to ignore `typescript >=6.1.0` so it stops proposing the bump every week. Lift the pin and that
  ignore entry together, once typescript-eslint supports 7.

## Review conventions

Nothing here fails CI. These are patterns to check for in review, named so they stay consistent.

- **A feature's `api/` is its anti-corruption layer.** It types each response with the generated
  wire type and maps it, member by member, onto the feature's own type in `types/`; nothing
  outside `api/` imports a generated type (ADR-029).
- **A feature's query keys come from one factory constant**, such as `accountKeys`, never a key
  literal spelled at the call site. A mutation invalidates only the keys that can have changed,
  never a wider prefix that would refetch data nothing touched.
- **A mutation's submit control disables itself while the mutation is pending**, and a submit
  event arriving anyway is ignored by an in-flight `useRef` guard, not the render-time `isPending`
  — two submit events can arrive before pending has rendered (ADR-028: no double submit).
- **A lifecycle date defaults to the browser's local today**, built through a helper that takes the
  date as an argument, such as `todayIsoDate(new Date())` — never `toISOString()`, which reads
  UTC, and never `now()` called inside the helper itself.
- **A mutation that can affect an unknown number of cached queries invalidates them by a
  hierarchical key prefix, not by enumeration or a predicate.** `useAddCategorisationRule` cannot
  list which budgets exist, so `budgetKeys` gives every review a shared parent —
  `reviews: ['budgets', 'reviews']` and `review(id): ['budgets', 'reviews', id]` — and the
  mutation invalidates `queryKey: budgetKeys.reviews`, a plain prefix match that reaches every
  review and nothing else, since a sibling such as `list` sits outside that prefix. Reach for
  `predicate` only when the affected keys cannot share a prefix without also catching a key that
  must not be invalidated. A mutation that already knows the affected ids (`useRecordTransaction`
  knows which accounts an entry set names) still invalidates each key directly. A test proves the
  scope through the query client's own state (`getQueryState(key)?.isInvalidated`), not just the
  call's arguments.
- **A date helper does calendar-part arithmetic on integers, never a `Date` parsed from a
  string.** `period.ts`'s month-to-period mapping and day-before calculation use plain year/month
  integers with a leap-year-aware days-in-month table. A `Date` is built only to hand an already
  computed instant to `Intl.DateTimeFormat`, pinned to `timeZone: 'UTC'` exactly as `DateText`
  does, so formatting cannot shift the result with the browser's own zone.
- **Cross-feature data enters a feature only through a context the feature declares and `src/app`
  provides.** Planning declares `AccountOptionsContext`, in its own terms, and
  `PlanningAccountsBridge` in `src/app` fills it from accounting's `useAccounts` (ADR-010,
  ADR-022). `tests/architecture.test.ts` enforces that no feature imports another; that the data
  arrives through a declared context rather than, say, a prop threaded from `src/app`, and that
  the hook reading it throws without a provider, is held in review.
- **A chart's scale function is the only place an amount becomes a number**, and only to compute a
  pixel coordinate — `scalePoints` in `features/planning/components/chartScale.ts`. Every figure a
  person reads — labels, axis extremes, tables, title text — is the server's string through
  `MoneyText` (ADR-029).
- **A form section scoped to one entity is keyed by that entity's id**, so navigating to another
  entity on the same route starts it afresh instead of carrying one entity's inputs or result onto
  the next. `HoldingPage` keys `DisposalSection` and its lookup by `symbol`; a test proves the
  reset by returning to an entity whose data is already cached, where only the key can cause a
  remount.
- **A lookup that can land on the URL already open invalidates that entity's key prefix**, since
  navigating to the same URL changes no query key and refetch on focus is off. `SymbolLookup`
  invalidates `portfolioKeys.holding(symbol)` through `useRefreshHolding`, pinned by a test
  asserting the whole invalidation set and that another cached entity stays valid.
- **Editing any field of a settled form clears its answer.** Each field's change handler calls
  `clearSettledOutcome(mutation, submitting)` from `src/shared/lib/settledMutation.ts`, which
  resets a mutation that has been sent, so its alert, its field errors or its result, such as a
  priced disposal, never stands beside inputs it no longer answers. A mutation in flight is left
  alone: resetting it would detach the `onSettled` that releases the in-flight guard, and the form
  would stay locked. The form's in-flight guard decides that, never the render-time status, which
  lags a tick behind both a resubmit and a settle (#71). Each form has a test that editing a field
  clears its failure, and, for the disposal, its result.
- **A failed read offers "Try again" only when `isRetryable(error)` holds** — a network failure,
  or a 5xx other than a 503. The predicate lives in `src/shared/api/retry.ts`, and the query
  client's automatic retry imports the same one, so the two never disagree; a 4xx is the server's
  considered answer, shown with nothing to press. Every feature renders a failed read through the
  shared `QueryErrorState` from `src/shared/ui`, which renders its `RetryButton`, never a copy of
  its own; moves focus to what a successful retry restores with `useRetryFocus`; and checks a
  problem's code with `hasProblemCode`, beside `ApiError`.
- **A write control reads `useWriteAccess` before it renders, and the server enforces access
  regardless.** `WriteAccessContext` (`src/shared/lib/writeAccess.ts`) is a UX courtesy alone,
  never a security boundary. `SessionGate` provides it from `writeAccessFor(session.roles)`
  (`src/app/session/access.ts`), and `renderRoutes`'s `access` option defaults to
  `{canWrite: true}` so a screen test written before this convention needs no change. A viewer
  sees every area but no control that changes data — `OpenAccountForm`, `RecordTransactionPage`,
  Clear/Reconcile/Void, `DefineBudgetForm`, `AddCategorisationRuleForm`, `PlanFlowForm`,
  Commit/Realise/Cancel and `DefineScenarioForm` all hide themselves this way — except
  `DisposalSection`, which stays offered because pricing a disposal is a read. A `FORBIDDEN`
  problem is rendered by `ErrorState` with a fixed sentence rather than the server's own detail,
  through the same `hasProblemCode` machinery, never a bespoke check.
- **A component that depends on its own size watches it with a `ResizeObserver`, never a window
  `resize` listener**, which misses a web font loading, a sibling appearing and most zoom changes
  (#72). `DataTable` observes both its scroll region and its table. jsdom has no
  `ResizeObserver`, so `src/shared/testing/browserGlobals.ts`, a `setupFiles` entry, installs
  `FakeResizeObserver` in every jsdom file, and a test reports a size change with `resizeElement`
  inside `act`.
