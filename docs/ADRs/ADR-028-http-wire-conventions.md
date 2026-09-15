# ADR-028: HTTP wire conventions, for both services

- **Status:** Accepted
- **Date:** 2026-09-14

## Context

[ADR-004](ADR-004-http-openapi-service-contract.md), ratified the same day, settles three things:
JSON over HTTP, a version in the path, and a committed OpenAPI document as the contract. It leaves
open everything a first controller has to decide: how the document is produced and kept honest, how
a decimal or a date is encoded, how a sealed type crosses the wire, what a create returns, which
status a refusal gets, and what a failure looks like.

[ADR-026](ADR-026-web-client-calls-both-services-through-one-origin.md) makes leaving them open more
expensive. Two services on two stacks now serve one browser client, and answering each question
once per stack is how one client ends up handling two answers.

**The constraints are already in force, and the wire has to carry them rather than bend them.**

- **Money is never floating point.** `money_is_never_floating_point` fails the ledger's build for a
  `double` field, and `…Then_it_names_no_float` fails the assistant's.
- **No domain function reads the clock, and neither does a use case.** Transitions take a date, and
  the clock is read once, at the edge, and passed down.
- **The aggregates are records with sealed lifecycles**, and `domain_depends_on_no_framework` and
  `the_application_layer_depends_on_no_framework` keep Jackson out of both layers.
- **Identity is generated inside the service**, never accepted on a command
  ([ADR-021](ADR-021-application-layer-shape.md)).
- **Nothing recorded is removed.** No repository port exposes a delete (ADR-021), and the journal is
  append-only in the database ([ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md)).
- **Only a composition root may see every context** ([ADR-022](ADR-022-ledger-composition-root.md)),
  so that is where anything mapping every context's failures has to live.
- `contracts/README.md` already requires that *"CI must compare the running document against the
  committed one once an endpoint exists"*.

## Decision

### Transport and contract

**JSON over HTTP, versioned in the path as `/api/v1/...`**, as ADR-004 decided.

**Contracts are generated from the code, committed as snapshots, and checked by a drift test.**

- **Generation.** springdoc-openapi 3.1.1 generates the ledger's document, through
  `springdoc-openapi-starter-webmvc-api` — the API starter, with no Swagger UI. FastAPI generates the
  assistant's from its routes.
- **Storage.** Both are committed as JSON under `contracts/`: `ledger-v1.openapi.json` and
  `assistant-v1.openapi.json`. That replaces the `ledger-v1.openapi.yaml` name `contracts/README.md`
  anticipated; one format serves both documents, and FastAPI's is JSON.
- **The drift test compares parsed trees, not bytes.** Key order and whitespace are not the contract,
  and a byte comparison would fail on a serialiser's formatting rather than on a change anyone made.
  On a mismatch the test writes the document the code actually produces to a git-ignored path, and
  fails naming the file to copy over the committed one. That is how `contracts/README.md`'s
  requirement is met: the comparison runs inside each service's own suite, which CI already runs.
- **Stability.** A document has to be deterministic before it can be compared:
  - the ledger's `OpenAPI` bean fixes `servers` to `/`, because springdoc otherwise embeds the URL it
    was served on, which in a test is a random port;
  - `springdoc.writer-with-order-by-keys=true`;
  - error responses are documented in both documents — by a global customizer on the ledger, and by
    replacing FastAPI's default `422 HTTPValidationError` on the assistant — so types generated from
    either carry the problem shape rather than omitting it;
  - `/health` and `/ready` are excluded from the assistant's schema. They are probes, not contract.

### Encoding

- **Every decimal is a JSON string, never a JSON number.** Money is
  `{"amount": "12.50", "currency": "ILS"}`, and growth rates and quantities are strings too. Most
  parsers read a JSON number into a binary float — `JSON.parse` in the browser among them — and money
  is never floating point. A string also keeps scale: `"12.50"` survives the trip where `12.5` would
  not, and scale is exactly what [ADR-018](ADR-018-cross-language-money-vectors.md)'s vectors pin.
- **Dates are ISO-8601 calendar dates**, such as `2026-09-14`. **A lifecycle date comes from the
  request body**, and the UI defaults it to today, in the browser. No server reads a clock to clear a
  transaction or commit a plan: the rule that no domain function reads the clock, applied at the
  edge.
- **Sealed domain types are mapped by hand to a discriminated object**, such as
  `{"status": "VOIDED", "on": "2026-09-01", "reason": "…"}` for a voided transaction's state, or
  `{"kind": "TIMES", "cadence": "MONTHLY", "interval": 1, "count": 12}` for a recurrence. The
  discriminator and each variant's fields are written out in a transport type and its mapper. No
  Jackson polymorphism annotation is used.
- **Ordered maps travel as arrays**, such as a budget review's unbudgeted spend and a comparison's
  projections. RFC 8259 leaves the order of an object's members unspecified, so a map whose order
  matters cannot be an object.

### Resource shape

- **A create is a `POST` to a collection, answered `201` with the created representation and no
  `Location` header.**
  - It never accepts an id: identity is generated inside the service.
  - There are no single-item reads (below), so a `Location` would name a URL that does not resolve.
  - A categorisation rule has no id at all, so `POST /categorisation-rules` returns the rule and
    nothing that identifies it.
- **A lifecycle command is a `POST` to a sub-resource** — `…/clear`, `…/reconcile` and `…/void` on a
  transaction; `…/commit`, `…/realise` and `…/cancel` on a planned flow — answered with the updated
  resource. A transition is a command carrying a date and sometimes a reason, not a field a client
  overwrites.
- **A side-effect-free computation is a `GET` with query parameters, unless an input is a monetary
  amount**, which travels in a `POST` JSON body answered `200`, so amounts never enter a URL (access
  logs, trace attributes, proxies): a projection and a comparison of scenarios are `GET`s, and
  pricing a disposal, whose proceeds are an amount, is a `POST`. None records anything, so each is
  safe to repeat.
- **No single-item reads while lists are unpaginated.** A deep link resolves from the cached list,
  which already holds the item.
- **List order is part of each port's contract**, not an accident of a query:

  | List | Order |
  |---|---|
  | Accounts | name |
  | Budgets | period start descending, then id |
  | Planned flows | start date, then id |
  | Scenarios | name, then id (names are not unique) |

- **Ledger controllers are component-scanned `@RestController`s, not `@Bean`s declared in
  `LedgerConfiguration`.** This is a deliberate, bounded exception to the composition root building
  everything by hand (ADR-022). `@WebMvcTest` slices load scanned controllers and skip scanned
  configuration, so a controller declared as a `@Bean` would be missing from exactly the test written
  to exercise it. The exception is the web layer's alone: application services stay hand-built beans
  in `composition`, and each in-port has exactly one implementing bean, so a controller that injects
  an in-port by type is never ambiguous.

### Errors

**Every failure is an RFC 9457 problem, served as `application/problem+json`, with a stable `code`
extension member.** `title` and `detail` are prose for a person; `code` is what a client branches
on.

| Situation | Status |
|---|---|
| Malformed request: unparseable JSON, a wrong type, a bad UUID, enum, date or decimal pattern, a missing field | 400, with `errors[]` |
| The resource named **in the path** does not exist; an unknown route | 404 |
| A conflict with current state: a duplicate account name, an illegal lifecycle transition | 409 |
| Well-formed but refused by a domain rule (`IllegalArgumentException`, a currency mismatch), or **an id in the body or query that does not exist** | 422 |
| A price is unavailable (assistant) | 503 |
| Anything else, including a missing plan during a projection and a response that fails its own model | 500, with a generic detail |

Two rows carry reasoning that is easy to lose.

- **Where a missing id appears decides its status.** An id in the path names the resource the request
  is about, so its absence is 404. An id in the body or query is part of what the request asks for —
  post these entries against this account, compare against this baseline — so the request is
  well-formed and refused, which is 422.
- **A missing plan during a projection is 500, not 404 or 422.** Nothing is deletable, so a scenario
  naming a plan the store cannot find means the store is corrupt, not that the request was wrong.

A 500 carries a generic detail, is logged at ERROR, and puts no exception text on the wire. An
exception message is among the likeliest carriers of internals and data — the same reason
[ADR-019](ADR-019-opentelemetry-and-structured-logging.md)'s redaction runs after a traceback is
rendered — and the log line carries the trace id that finds it.

**Ledger mechanics.**

- A fallback advice in `composition`, at lowest precedence, extending
  `ResponseEntityExceptionHandler`, so Spring's own 404, 405 and 415 keep their statuses instead of
  falling through to 500.
- A `@RestControllerAdvice(basePackageClasses = …)` per context, at highest precedence, mapping that
  context's typed exceptions.
- Controller-local handlers where an id comes from the body or query, since the same not-found
  exception is 404 from a path and 422 from a body.

**Assistant mechanics.** The handlers are registered in the composition root, `adapters/problems.py`
— the only package allowed to see every context's exceptions.

## Consequences

**Makes easy**

- One error shape from two stacks, for one client: the web client parses a problem and nothing else.
- Types generated from either document carry exact decimal strings and the problem shape, because
  the documents describe them. A sealed type is flat on the wire — a required `status`, with
  optional `on` and `reason` — and each web feature's `api/` mapper builds the discriminated
  union from it, because the document has no discriminated variant to generate one from.
- Contract drift is a failing test that names the file to copy, rather than a mismatch found in a
  browser.
- A status tells a client what to do next: fix the request (400), stop looking (404), reconsider in
  the light of current state (409), change what was asked (422), try again later (503), or report a
  bug (500).

**Makes hard**

- Every sealed type is mapped by hand in both directions, per context, and a new variant touches the
  domain, the mapper, the transport type and the committed document.
- A decimal string is a string to the schema. Only its pattern says it holds a number, and a type
  generated from the document says `string`.
- The committed documents must be regenerated with every endpoint change, or the build fails. That is
  the point, and it is still a step to remember.
- The controller exception to ADR-022 is a hole, and holes grow. "Scanned because the test slice
  needs it" must not become the reason anything else is scanned.

**Accepted cost**

- **`IllegalArgumentException` is not the domain's only rule signal — `IllegalTransitionException`
  and `IllegalPlanTransitionException` are rule signals too, mapped to 409 by their own context's
  advice — but it is the only one the composition fallback conflates with a bug**, so a bug that
  raises `IllegalArgumentException` reads as 422, not 500. Syntax is parsed before the domain sees
  input — UUID-typed parameters, enums, decimal patterns — so 400 and 422 stay distinct; what
  cannot be told apart is a rule the domain meant from one it hit by mistake.
- **The assistant's `ValueError` is its `IllegalArgumentException`, with the same conflation.**
  `adapters/problems.py` answers every plain `ValueError` no more specific handler claims with 422
  `RULE_VIOLATED`, and puts the exception's message in the detail. That is safe only while every
  plain `ValueError` reachable is a domain rule about the caller's input — a blank symbol, a
  disposal of no units, an amount finer than its currency — as each is today. Python raises
  `ValueError` for far more: `int()`, `UUID()`, `date.fromisoformat()`, `json.loads()` and a failed
  decode among them, so a parse failure in an outbound adapter would be answered 422, with its
  internal message on the wire.
- **A double-submitted create is permanent.** The journal is append-only, so the only remedy for a
  duplicated transaction is to void it. The UI disables submit while a request is pending, which
  narrows the window without closing it.
- **No pagination.** Every list returns everything it holds.
- **The accounts screen makes one balance request per account.**

## Alternatives considered

**Contract-first, with server stubs generated from a hand-written document.** The document would lead
and the code would follow. Rejected: it adds a generator to both toolchains, and the generated
interfaces would fight the hand-built composition root that ADR-021 and ADR-022 established.

**A hand-written document with no generator.** No new dependency at all. Rejected: it drifts
silently, because nothing ties it to the handlers. It was kept as the fallback had springdoc 3.1.1
turned out not to support Spring Boot 4.1.1, and would then have carried a test asserting that
every handler mapping appeared in it. springdoc runs here, so the fallback was never needed.

**JSON numbers for money.** The natural encoding, and what most APIs do. Rejected: most parsers read a
JSON number into a float, so `12.50` arrives as `12.5` with its scale gone, and a value with no exact
binary form arrives as its nearest approximation.

**Spring's default error body, or FastAPI's `{"detail": …}`.** No code to write. Rejected: two error
shapes for one client, and neither carries a stable member to branch on.

**Jackson polymorphic type annotations on domain types.** Less mapping code. Rejected:
`domain_depends_on_no_framework` and `the_application_layer_depends_on_no_framework` forbid Jackson in
both layers, and mapping by hand keeps the wire shape explicit in one place rather than implied by
annotations on an aggregate.

## Revisit when

- **The domain gains a typed rejection exception.** Map it to 422, and let a stray
  `IllegalArgumentException` fall through to 500, where a bug belongs.
- **Voiding stops being an acceptable remedy for a duplicated create.** Add an `Idempotency-Key`
  header.
- **A list exceeds about 1,000 items, or its latency is measured as a problem.** Paginate it. The
  per-account balance requests share this trigger.
- **Single-item reads are added.** A create then sends `Location`, because it has a URL to name.
- **The assistant's first outbound adapter lands** — a ledger client or a price feed. It converts
  its parse failures to typed exceptions of its own, or the `ValueError` entry in
  `adapters/problems.py` is narrowed, so that a parse failure is not answered as a refusal.
