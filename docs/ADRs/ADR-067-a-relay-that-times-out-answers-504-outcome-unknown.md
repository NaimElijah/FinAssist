# ADR-067: A relay that times out answers 504, outcome unknown

- **Status:** Accepted
- **Date:** 2026-10-31

Extends [ADR-053](ADR-053-the-gateways-outbound-calls-speak-http-1-1-under-one-time-budget.md),
which bounds every relayed request by one read budget and answers a request that exceeds it with
502 `UPSTREAM_UNAVAILABLE`. It also extends
[ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md), whose failure-code table lists
the gateway's codes and gains one here.

## Context

**Scan 7 of 12 read the services' HTTP contracts on 2026-10-31**
([#344](https://github.com/NaimElijah/FinAssist/issues/344)). It found one answer covering two
failures with opposite consequences.

**`GatewayRelayProblems` answered every I/O failure of the relay the same way**: 502
`UPSTREAM_UNAVAILABLE`, with the detail "The upstream service could not be reached." That is true
of a connection that was never opened. It is false of a read timeout.

- **A connection never opened** means the request was never sent. Nothing happened upstream, and
  sending it again is safe.
- **A read timeout** means the request was sent, and the ledger or the assistant did not answer
  within ADR-053's 10 seconds. The upstream may have committed it.

**For a write, the wrong answer creates a duplicate.** The owner records a transaction, the ledger
commits it and then answers late, and the web client shows "could not be reached" verbatim, as
`ErrorState` shows every problem's detail. The owner sends it again. The journal is append-only,
so the second transaction can only be voided. ADR-028 accepts that "a double-submitted create is
permanent", and ADR-056 leaves a retried write to the owner's click, since no `Idempotency-Key`
exists. The owner's click was being invited by a detail that was wrong.

**RFC 9110 separates the two.** 502 (§15.6.3) is an invalid response from an upstream; 504
(§15.6.5) is "did not receive a timely response from an upstream server".

**The two failures can be told apart by type, and only by type.** Read from spring-web 7.0.10's
bytecode and Boot 4.1.2's `JdkClientHttpRequestFactoryBuilder`, then observed against a real
`RestClient` on the gateway's own classpath:

- **Read timeout.** Boot sets `read-timeout` on `JdkClientHttpRequestFactory`. Its request starts a
  timer when it hands the request to the JDK client, cancels the exchange when it fires, and
  rethrows the cancellation as a bare `java.net.http.HttpTimeoutException` ("Request cancelled").
- **Connect timeout.** Boot sets `connect-timeout` on the JDK `HttpClient`, which raises
  `HttpConnectTimeoutException`, caused by a `ConnectException`. It **extends**
  `HttpTimeoutException`, so a test for the parent alone calls it a read timeout.
- **Refused, or unresolvable.** A `ConnectException`, caused by a `ClosedChannelException` or an
  `UnresolvedAddressException`.

`RestClient` wraps each in a `ResourceAccessException` whose message names the method and the URL,
query included. No message says "timeout" reliably: the read timeout's says "Request cancelled".

## Decision

**A relayed request that was sent and not answered within the read budget is a 504
`UPSTREAM_TIMEOUT`.** Its detail is fixed: "The upstream service did not answer in time, so the
outcome is unknown: a change you sent may already have been applied. Check before sending it
again."

**A connection that was never opened stays a 502 `UPSTREAM_UNAVAILABLE`**, with its detail
unchanged: a refused connection, an unresolvable host, and a connect timeout.

**The failure is classified by type, on the cause chain, never by message.**
`GatewayRelayProblems.upstreamFailed` walks the chain under the `ResourceAccessException`:

- The first `HttpTimeoutException` it meets decides.
- `HttpConnectTimeoutException` is tested first, and is a 502.
- Any other `HttpTimeoutException` is a 504.
- A chain with neither is a 502, since only a timeout is known to have been sent.
- The walk stops at a cause it has already seen, because `initCause` can close a loop.

**Each is logged at WARN, in a line of its own**, fixed text only. Neither line carries the
exception, whose message names the relayed URL and its query. A timeout is not the gateway failing,
so it is not ERROR.

**The answer does not depend on the method.** A `GET` that times out is a 504 too. The status
describes what happened at the hop, and a read has nothing to duplicate.

**The code is declared beside the others**, in `GatewayProblems`. ADR-030's failure-code table gains
a row:

| Code | Status | Returned by | Headers |
|---|---|---|---|
| `UPSTREAM_TIMEOUT` | 504 | gateway | — |

**The web client needs no change.** ADR-056's `isRetryable` holds for every status of 500 or more
other than 503, so a read that times out is retried once, and a write is never retried.

## Consequences

**Makes easy**

- **The owner is no longer told that a change failed when it may have been applied.** The detail
  says to check first.
- **A client can tell "never sent" from "outcome unknown"** by status and by code, without reading
  prose.
- **The statuses mean what RFC 9110 says they mean.**

**Makes hard**

- **The gateway cannot find out what happened.** The owner checks by hand, for example the
  account's journal, since no endpoint looks a request up.
- **A failed read can now take twice the budget to show.** The web client retries a 504 read once,
  so the owner waits up to about 20 seconds.
- **The read budget includes the connection.** The timer starts when the request is handed to the
  JDK client, before a connection exists. If `read-timeout` were ever set at or below
  `connect-timeout`, a connection stuck opening would be answered 504, though nothing was sent.
  That errs towards caution, never towards a resend, and the shipped 5 s and 10 s keep them apart.
- **The classification is typed to the JDK client.** If an HttpComponents, Jetty or Reactor client
  reaches the classpath, its read timeout is another type and falls to 502.
  `GatewayRelayTimeoutIT` turns red when it does, as ADR-053's pin tests do.

**Accepted cost**

- **A reset after the request was sent still answers 502 "could not be reached".** A connection
  the upstream drops after receiving the request arrives as a plain `IOException` ("HTTP/1.1 header
  parser received no bytes", caused by `SocketException: Connection reset`), and its outcome is as
  unknown as a timeout's. Nothing in that chain says whether the request was written, so it cannot
  be told by type from other I/O failures. This record covers the timeout alone.
- **A failure after the response has started is not covered.** Once the upstream's headers are
  relayed, the response is committed and no handler can change its status.
- **A duplicate is still possible.** The 504 tells the owner to check; it does not prevent the
  second click. ADR-028's trigger for an `Idempotency-Key` stands.

## Alternatives considered

- **Keep the 502 and reword its detail.** Rejected: a client branching on the status or the code
  could still not tell the two apart, and 502 for a timeout contradicts RFC 9110.
- **Answer every I/O failure 504.** Rejected: a refused connection was never sent, and telling the
  owner it may have been applied sends them to check for nothing. It also loses "safe to resend".
- **Classify by the exception's message.** Rejected: messages are not a contract, and the read
  timeout's message, "Request cancelled", does not say "timeout".
- **504 for a write, 502 for a read.** Rejected: the status says what happened at the hop, not what
  was asked, and a read is retried once either way.
- **Add an `Idempotency-Key` now.** Not adopted here. It changes the ledger's contract, and ADR-028
  owns that decision and its trigger. A scan finding fixes the answer, not the protocol.
- **Raise the read budget.** Rejected: a longer budget makes the case rarer, not impossible.

## Revisit when

- **An `Idempotency-Key` exists on the creating endpoints.** A resend becomes safe, and the detail
  can say so.
- **A second HTTP client reaches the classpath.** Classify its read timeout's type.
- **A relayed endpoint streams its response.** Failures after the headers start to matter.
- **The JDK client or Spring reports whether a request was written.** The reset-after-send case can
  then become a 504 too.

## What lands with this record

- **The code.** `GatewayProblems.UPSTREAM_TIMEOUT`, and `GatewayRelayProblems.upstreamFailed`, which
  replaces `upstreamUnavailable`.
- **The tests.**
  - `GatewayRelayTimeoutIT`: a ledger that accepts a relayed request and never answers is given up
    on within the budget and answered 504 `UPSTREAM_TIMEOUT` with the detail above.
    `Given_the_ledger_accepts_a_relayed_request_and_never_answers_When_relayed_Then_the_relay_gives_up_with_504_outcome_unknown`
    replaces the `..._with_502` test that ADR-053 names.
  - `GatewayRelayProblemsTest`: a read timeout is 504; a connect timeout, a refused connection and
    an unresolvable host are each 502; a looping cause chain ends in a 502; the two lines are
    distinct WARNs that carry neither the URL nor the exception.
  - `GatewayLoginFlowIT.UpstreamsDown` still holds a refused connection to 502
    `UPSTREAM_UNAVAILABLE`, unchanged.
- **Documentation:**
  - `docs/architecture/architecture.md` and `docs/testing/testing.md`;
  - `backend/gateway/CLAUDE.md`'s rules, and the health comment in the gateway's `application.yml`;
  - ADR-053 and ADR-030 read `extended by … ADR-067`, as do their rows in `docs/ADRs/README.md`,
    with a row and a note for this record.
