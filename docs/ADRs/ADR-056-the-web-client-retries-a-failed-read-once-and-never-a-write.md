# ADR-056: The web client retries a failed read once, and never a write

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-029](ADR-029-web-client-stack.md), which chose TanStack Query for server state and
records no retry policy, and [ADR-028](ADR-028-http-wire-conventions.md), which gives 503 a meaning
and accepts that a double-submitted create is permanent.

## Context

**TanStack Query retries a failed query three times by default, and a mutation not at all.** The
web client's behaviour is set in two files, and no record explains it
([#339](https://github.com/NaimElijah/FinAssist/issues/339)): `src/shared/api/retry.ts` and
`src/app/queryClient.ts`. The rule has been there since `01e02f6` (2026-09-14).

**The services answer a failure in ways a client can tell apart.**

- A 4xx is the server's considered answer. A 404 stays missing and a 409 stays in conflict, so a
  second attempt only repeats it.
- A 503 is the assistant's `PRICE_UNAVAILABLE`, a price feed gone stale. ADR-028 reads 503 as "try
  again later", but a stale feed does not recover within a retry's backoff, so a retry would only
  delay showing that no price is usable.
- A backend restarting refuses the connection, which `fetch` reports as a `TypeError`, or answers
  502 through the gateway (`UPSTREAM_UNAVAILABLE`). Compose restarts a crashed service
  ([ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md)).

**A retried write can create twice.** The journal is append-only, so the only remedy for a
duplicated transaction is to void it. ADR-028 accepts this: "A double-submitted create is
permanent." The UI disables submit while a request is pending, which "narrows the window without
closing it". No `Idempotency-Key` exists.

**Refetching on focus adds requests for nothing.** The owner is the only writer, and every write
invalidates what it affects.

**A session the gateway ended mid-visit goes unnoticed.** The gateway keeps sessions in memory
([ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)). A restart ends them, and every
screen would then answer 401 until the owner reloaded.

## Decision

**A failed read is retried at most once, and only when a second attempt could answer differently.**

- **What is retryable.** `isRetryable` in `src/shared/api/retry.ts` holds for a network failure,
  which is a `TypeError`, and for an `ApiError` with a status of 500 or more other than 503.
  Everything else is a considered answer, or a defect a second attempt would repeat.
- **How often.** `MAX_QUERY_RETRIES` is 1, in `src/app/queryClient.ts`. One retry absorbs a dropped
  connection or a backend restarting, without hiding a real outage behind several backoffs.
- **One rule, two uses.** The failed read's "Try again" control is offered by the same predicate
  (`QueryErrorState`), so the automatic retry and the button never disagree.

**A write is never retried.** `MUTATION_RETRIES` is 0, whatever failed.

**A read is not refetched on window focus.** `REFETCH_ON_WINDOW_FOCUS` is false.

**A 401 from anything but the session check invalidates the session.** The `QueryCache` and
`MutationCache` handlers recheck `GET /bff/me` once. The session query's own 401 is excluded, since
invalidating it would refetch it, which would 401 again, in a loop.

## Consequences

**Makes easy**

- **A backend restart is invisible to the owner** in the ordinary case, since one retry covers it.
- **A write cannot be sent twice by the client's own machinery.** Only the owner's click can.
- **The retry button and the automatic retry agree**, because they share one predicate.
- **An ended session is noticed** the next time anything asks.

**Makes hard**

- **A 503 is never retried and never offers "Try again".** That includes the ledger's and the
  assistant's `AUTHENTICATION_UNAVAILABLE` (ADR-030), a key-source outage that may clear in
  seconds. The owner reloads.
- **A pure write is not retried even when it is safe.** Pricing a disposal is a `POST` that records
  nothing, but it is a mutation in the client, so a dropped connection needs a second click.
- **A defect that throws a `TypeError` is retried once.** From where the predicate sits the two
  cannot be told apart, and `retry.ts` says so.
- **A failed read is sent twice when a 500 is a defect.** The second attempt repeats it.

**Accepted cost**

- **A double click can still create twice.** The in-flight guard narrows the window, as ADR-028
  accepted. This record adds no guard against it.

## Alternatives considered

- **TanStack Query's default: three retries with backoff.** Rejected: it hides a real outage behind
  several attempts, each waiting out its own backoff, and it retries a 404.
- **Retry every 5xx, 503 included.** Rejected: a stale price feed does not recover inside a retry's
  backoff, so the owner would wait to be told what they could have been told at once.
- **Retry a write once, as a read.** Rejected: a retried `POST` can create twice, and voiding is the
  only remedy.
- **No retries at all.** Rejected: a backend restarting under Compose is what one retry absorbs.
  Without it the owner would see an error for it.
- **Refetch on window focus.** Rejected: the owner is the only writer, so it only adds requests.

## Revisit when

- **An `Idempotency-Key` exists on the creating endpoints.** A write can then be retried safely, and
  ADR-028's own trigger for it ("voiding stops being an acceptable remedy") is the usual cause.
- **A 503 other than `PRICE_UNAVAILABLE` is common.** The key-source outage, for example, may
  deserve a retry, and `SERVICE_UNAVAILABLE_STATUS` would be split by code.
- **A second person uses FinAssist.** Other writers make refetch on focus worth its requests.

## What lands with this record

No code. Every rule above exists and has a test:

- **The predicate.** `retry.test.ts` pins the retryable and non-retryable cases.
- **The client's defaults.** `queryClient.test.ts`:
  `Given_the_app_query_client_When_its_mutation_defaults_are_read_Then_mutations_never_retry`,
  `Given_the_app_query_client_When_its_query_defaults_are_read_Then_queries_do_not_refetch_on_focus`,
  and the cases for a query attempted once or retried exactly once.
- **The 401 handler.** `queryClient.test.ts`:
  `Given_a_401_from_a_query_other_than_the_session_check_When_it_fails_Then_the_session_is_invalidated_once`
  and its sibling for the session check's own 401.

Documentation:

- **Status lines and README.** ADR-028 reads `Accepted, extended by ADR-030 and ADR-056`, and
  ADR-029 `Accepted, extended by ADR-030, ADR-047 and ADR-056`, each with its row in
  `docs/ADRs/README.md`, a row for 056 and a note.
- **`frontend/web/CLAUDE.md`.** The "Try again" rule cites ADR-056.
