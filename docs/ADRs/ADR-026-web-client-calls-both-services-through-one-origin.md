# ADR-026: The web client calls both services directly, through one origin

- **Status:** Accepted
- **Date:** 2026-09-14

## Context

The first open question in [requirements](../requirements/requirements.md#open-questions) asked:
*"Does the web client talk only to the assistant, or also to the ledger directly? Talking to both
means two contracts and two auth stories; going through the assistant makes it a gateway for
traffic that has nothing to do with assistance."* The owner answered it on 2026-09-14, together
with which screen areas are in scope: accounts and transactions, budgets, planning, and portfolio.

Three of those four areas belong to the ledger. `accounting`, `budgeting` and `planning` live in
`backend/ledger` ([ADR-010](ADR-010-bounded-context-map.md),
[ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md)); only `portfolio` is the assistant's.

**The ledger's contract is required whichever way the question is answered.**
[ADR-004](ADR-004-http-openapi-service-contract.md) makes it the document the assistant is a client
of, and [ADR-007](ADR-007-asymmetric-hexagonal-architecture.md) names it the Published Language
between the two. A second consumer of that document costs nothing the first has not already paid
for.

**Routing ledger traffic through the assistant does cost something.** The assistant would become a
gateway for requests that have nothing to do with assistance — opening an account, voiding a
transaction. Every ledger DTO would be restated in Python with nothing to translate, which is an
anti-corruption layer mapping a shape onto itself: the "mapping layers that map nothing" ADR-007
warns against. And a context-first codebase has no home for a gateway. A pass-through for three
ledger contexts belongs to none of the assistant's own three, and its composition root wires use
cases rather than serving another service's.

**There is a tension with [ADR-009](ADR-009-react-vite-spa-for-the-web-client.md), and it is named
here rather than left to be found.** ADR-009 rejected a server-rendered UI from the ledger partly
because *"the assistant, not the ledger, is the service a person talks to"*. That sentence was about
where presentation lives — whether pages should be rendered inside the service that owns the money
invariants — and it did not choose a network topology. Presentation stays in the web client under
this record, and assistance remains the assistant's to serve. What changes is that reading and
recording the books is no longer routed as though it were assistance.

**Browsers add a constraint of their own.** The ledger listens on 8080 and the assistant on 8000, so
a page served from any third port that calls either makes a cross-origin request, which each
service would then have to grant.

## Decision

**The React client calls the ledger directly for the accounting, budgeting and planning screens,
and the assistant for the portfolio screens.** "Ask a question" stays out of scope while the
requirements' LLM privacy question is open, so `assistance` gets no screen under this record.

**Each service publishes its own versioned contract**: `contracts/ledger-v1.openapi.json` and
`contracts/assistant-v1.openapi.json`. The web client consumes both, and the assistant remains a
client of the ledger's.

**In development, and under `vite preview`, one origin serves both.** The Vite proxy maps `/ledger`
to `http://127.0.0.1:8080` and `/assistant` to `http://127.0.0.1:8000`, stripping the prefix, so
`/ledger/api/v1/accounts` in the browser reaches the ledger as `/api/v1/accounts`. The browser never
makes a cross-origin call, and **no CORS configuration exists** on either service.

**The built static bundle has no server yet.** `npm run build` produces files that nothing serves
outside `vite preview`. Which origin serves them, and what fronts the two services from that origin,
is undecided until a deployment target is chosen. This record answers the question for development
only, and says so.

## Consequences

**Makes easy**

- Each screen talks to the service that owns its data, in that service's vocabulary, with no
  pass-through layer to keep in step with either contract.
- The assistant's contract describes portfolio and nothing else. It does not re-export the
  ledger's.
- One origin means relative URLs, `fetch` and the browser's same-origin rules behave as they would
  behind a single server, and there is no preflight to reason about.
- The ledger's contract gains a consumer before the assistant's ledger client exists — its
  `LedgerPort` is satisfied by an in-memory stand-in today — so a shape that is awkward to consume is
  found by a screen rather than later.

**Makes hard**

- Two contracts, two sets of generated types, and two services' failures for one client to handle
  alike: the "two contracts" the open question named. That stays cheap only if both services answer
  failures in the same shape.
- The proxy is development tooling standing in for topology. A path a service writes into a response
  names its own `/api/v1/…`, not the prefixed path the browser used, and the proxy hides that
  difference in development rather than exposing it.
- Authentication, when it arrives, has two services to terminate at rather than one: the "two auth
  stories" the question also named.
- The deployed shape is unanswered. The first deployment must decide what serves the bundle and
  fronts both services, and until then `npm run build` has nothing to run under.

**Accepted cost**

- A screen needing ledger data joined with portfolio data would have to join it in the browser. No
  screen in scope does, and the last revisit trigger below is aimed at the first that would.

## Alternatives considered

**The assistant as the sole gateway — a backend-for-frontend.** One origin in every environment, one
contract for the client, and one place to authenticate later. Rejected: it makes the assistant a
gateway for traffic that has nothing to do with assistance, it restates every ledger DTO in Python
with nothing to translate, and a pass-through for the ledger's three contexts has no home in a
codebase organised by context.

**CORS on both services.** No proxy, and the same request topology in development as anywhere else.
Rejected: it needs allowed origins, methods and headers configured on two stacks, and kept in step,
to permit a call that a same-origin proxy makes unnecessary.

**A reverse proxy container, nginx or similar, in Compose.** Production-shaped from the start, and it
could serve the built bundle too. Rejected: it is production topology chosen with no deployment
target to fit it to, and it adds a container to the inner loop of every UI change.
[ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md) makes Compose how the stack runs; it does
not make Compose the place to guess how the stack will be deployed.

## Revisit when

- **Authentication arrives**, and terminating it once becomes cheaper than terminating it at both
  services.
- **A deployed origin must front both services.** Choosing a deployment target ends this record's
  development-only answer, and the proxy's job then has to be done by something that runs outside
  `vite`.
- **The assistant gains a screen that needs ledger data joined server-side.** A gateway for that
  screen would be translating rather than passing through, and the case against the first
  alternative changes.
