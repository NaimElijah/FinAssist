# ADR-069: The web client targets WCAG 2.2 AA and holds it with tests

- **Status:** Accepted
- **Date:** 2026-10-31

Extends [ADR-029](ADR-029-web-client-stack.md). ADR-029 chose the web client's stack and says
nothing about accessibility. This record settles the standard the screens are held to, what holds
them there, which component owns focus and the document title, and what is deliberately not yet
done.

## Context

**Scan 8 of 12 reviewed the web client for accessibility on 2026-10-31**
([#345](https://github.com/NaimElijah/FinAssist/issues/345)). The client had a skip link and
labelled controls, and no stated standard and no tooling behind it.

- **No target existed.** `requirements.md` did not ask for one, so a finding had nothing to be
  measured against. The owner accepted WCAG 2.2 level AA the same day.
- **jsdom computes no styles.** A screen test cannot see a contrast ratio, a focus ring or a
  hidden element, though it can read a role, a name, a description and which element has focus.
- **Focus and the title were left to the browser.** A single-page app changes neither on a route
  change, and an h1 replaced once its data loads drops focus to the body.
- **Some screens render where the layout is not.** The session check, sign-in and the outage show
  above the router, and a render failure above it unmounts the layout, so none had a `<main>`, an
  h1 or a document title.
- **Announcements were inconsistent.** A bare paragraph inserted with its text in it may be read
  by nothing, since the browser can expose the region and its text in one update.
- **No automated checker runs.** [#78](https://github.com/NaimElijah/FinAssist/issues/78) asks
  for `eslint-plugin-jsx-a11y` and an automated audit, and has not been done.

## Decision

**WCAG 2.2 level AA is the web client's target**, as `requirements.md` states it. The decisions
below are how the client meets it and how a regression is caught.

**Tests hold it, since no checker runs.** Two kinds, each doing what the other cannot:

- **Testing Library suites assert what a screen reader reads**: role, accessible name, accessible
  description and which element has focus. Each fix of scan 8 has one.
- **`frontend/web/tests/contrast.test.ts` measures contrast.** It runs under the node environment
  and reads `tokens.css` and the stylesheets from disk, in both themes. A rule that sets a text
  and a background colour, each a single token, is measured at 4.5:1 without being listed.
  `INHERITED_PAIRS` lists by hand the pairs a rule completes only by inheritance, each at 4.5:1 or
  3:1. A rule whose colour or background is not a single token, such as a `color-mix()` or
  `transparent`, is not measured and fails the build until `UNMEASURED_PAIRS` lists it with the
  reason its contrast holds anyway.

**`Layout` owns focus and the document title.** On a navigation it moves focus to the page's h1,
made focusable by script, or to `<main>` where a page has none; a replace such as the `/` redirect
leaves focus alone. A `MutationObserver` follows an h1 replaced once its data loads, but only while
focus still rested on the old one. `Layout` names that heading to `SessionGate` through
`PageFocusTargetContext`, so a successful session retry lands there too. A route's title is found
in one place, `deepestRouteTitle`, and the document title is built in one, `documentTitleFor`.

**`StandalonePage` is every whole screen outside the layout**: the session check, sign-in, the
outage, `RootRouteError` and the `ErrorBoundary` fallback. It gives them the `<main>`, the h1 and
the title `Layout` gives a page. A failure of one area is a `RouteError` inside the layout; a
failure of `Layout` itself is a `RootRouteError`, so the screen still has exactly one h1.

**`LoadingNote` and `StatusLine` are how the client announces.** Each is a `role="status"` region
that is on the page before its text, so the text arrives as a change to it.

**An automated checker is rejected for now.** Neither `axe-core` nor `eslint-plugin-jsx-a11y`
is adopted here; revisit with [#78](https://github.com/NaimElijah/FinAssist/issues/78).

**Deferred to the owner: WCAG 3.3.4 for a write into the append-only books**
([#411](https://github.com/NaimElijah/FinAssist/issues/411)). Whether recording without a confirm
step meets it is a decision about the product, not a defect.

**Known gaps, not claimed as met:** a retry that fails again leaves focus on the body
([#412](https://github.com/NaimElijah/FinAssist/issues/412)); a retry paused offline is taken for
a settled read ([#413](https://github.com/NaimElijah/FinAssist/issues/413)); and a manual pass with
a screen reader, zoom and reflow, forced colours and real browsers, which is the owner's.

## Consequences

**Makes easy**

- **A regression in a name, a description, focus or a colour pair fails the build.** The cause is
  named in the test: the pair and its ratio, or the control and its role.
- **One screen's accessibility is read in one place.** A route's title, its h1, its document title
  and where focus lands come from the same two functions and one component.
- **A new full-page failure inherits the structure.** Anything rendered through `StandalonePage`
  has a `<main>`, an h1 and a title without repeating them.

**Makes hard**

- **The tests assert the roles a screen reader reads, never what a person sees or hears.** A
  criterion no test measures is unverified, and the manual pass is outstanding. The requirement is
  not claimed as met until it is done.
- **A new stylesheet rule can fail the build.** One whose colour or background is not a single
  token needs an entry in `UNMEASURED_PAIRS` with a reason, and a pair set by inheritance needs
  one in `INHERITED_PAIRS`. A restyle ([#230](https://github.com/NaimElijah/FinAssist/issues/230))
  has to keep both honest.
- **Every page needs its own h1**, and a row action's name is its word then its row, so a test
  looks it up with `nameStartingWith`, or `nameIsOrStartsWith` where it asserts an absence.
- **Focus management is code, not a browser default.** The `MutationObserver` in `Layout` is the
  subtlest part: it must not take focus from a person who has moved on.

## Alternatives considered

- **Add `axe-core` or `eslint-plugin-jsx-a11y` now.** Rejected for now: scan 8 fixed what its
  review found and pinned each fix with a test, and choosing and tuning a rule set is
  [#78](https://github.com/NaimElijah/FinAssist/issues/78)'s own work. `axe-core`'s contrast rule
  needs the computed styles jsdom lacks, so `contrast.test.ts` stays either way.
- **Measure contrast in a real browser.** Rejected: the client's tests run under jsdom and no
  browser test layer exists. Reading the stylesheets and tokens measures the same pairs without
  one.
- **Let the browser own focus on navigation.** Rejected: a single-page app's route change moves
  nothing, so a keyboard or screen-reader user stays where the old page left them.
- **Give each outside-the-layout screen its own markup.** Rejected: five screens would each
  repeat the `<main>`, the h1 and the title, and each could forget one.
- **Add a confirm step to every write to meet 3.3.4.** Not decided here: it changes what the
  product does. It is [#411](https://github.com/NaimElijah/FinAssist/issues/411)'s question.

## Revisit when

- **[#78](https://github.com/NaimElijah/FinAssist/issues/78) is taken up.** An automated checker
  changes which of these tests are still needed.
- **The owner decides [#411](https://github.com/NaimElijah/FinAssist/issues/411).** A confirm step
  or a reversal changes the write screens and this record's deferral.
- **The manual pass finds what the tests cannot.** Add the missing assertion, or supersede this
  record if the approach was wrong.
- **A browser test layer arrives.** Contrast and focus could then be measured as drawn.

## What lands with this record

- **The standard:** the Accessibility row of `requirements.md`'s non-functional requirements.
- **The structure:** `Layout`, `StandalonePage`, `RouteError`, `RootRouteError`, `ErrorBoundary`,
  `PageFocusTargetContext`, `deepestRouteTitle` and `documentTitleFor`.
- **The announcements:** `LoadingNote`, `StatusLine` and `useRowRemovalFocus`.
- **The tests:**
  - `tests/contrast.test.ts` holds every colour pair, with `INHERITED_PAIRS` and
    `UNMEASURED_PAIRS`.
  - `App.test.tsx`, `SessionGate.test.tsx`, `SignIn.test.tsx`, `ErrorBoundary.test.tsx`,
    `src/app/Layout.test.tsx` and `src/app/RouteError.test.tsx` hold the page structure and where
    focus lands.
  - The feature suites hold each screen's announcements, field labels and row-action names.
