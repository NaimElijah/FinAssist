# ADR-058: Each service bounds what one request can cost

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-028](ADR-028-http-wire-conventions.md), which fixes the wire's shapes and its
problem codes and sets no bound on how large or how costly a request may be. It also extends
[ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md), whose gateway relays a body on
as it arrives and whose services deny by default but read a request's body before they know who
sent it.

## Context

**Nothing bounded what one request could make a service do** before a rule looked at it
([#340](https://github.com/NaimElijah/FinAssist/issues/340)). Authentication decides who may ask;
it does not decide how much a request may cost, and in every service the cost is paid first.

**The body is read before the permission is checked.**

- **The assistant.** FastAPI reads and parses a route's whole body before any of its
  dependencies runs, the permission check included. The one body it serves, a disposal, is a few
  hundred bytes.
- **The ledger.** Nothing bounded the size of a JSON body. The largest one it takes, a
  transaction, a budget or a scenario, is a few kilobytes at most.
- **The gateway.** It streams a relayed body on as it arrives. A refusal left to the service
  would come part-way through that stream, and a body far over the limit could end with the
  connection closed and a 502, not the 413 the service answers. Nothing the gateway serves takes a
  body of more than a few kilobytes.
- **A decimal costs more than its length.** Parsing one grows faster than the digits it holds, and
  every amount, rate and quantity on the wire is a decimal string (ADR-028).

**Three ledger inputs set the work a request does.**

- **A horizon.** A projection computes and holds every step of its horizon at once, so an
  unbounded span is an unbounded allocation.
- **A comparison's scenario list.** Each scenario is projected in full, so a longer list is more
  of the same work.
- **A date.** `java.time` and PostgreSQL each accept dates hundreds of thousands of years from
  today, and not the same ones, so the ledger bounds a date to the years it means.

**A request the firewall refuses reached Boot's error body on the ledger.** Spring Security's
firewall refuses a URL it cannot route safely before any filter runs, and the default handler
leaves Boot to render a body with no `code` member that echoes the caller's path. The gateway
already answered it in its own problem shape (ADR-057).

**An unparseable multipart body is now a 400.** Spring MVC parses multipart before it chooses a
route, a `GET`'s included. The ledger takes only JSON, so a body it cannot parse as multipart is
the request's fault, and not a failure of the ledger's.

**The assistant's server accepted what its framework did not bound.** uvicorn picks `httptools`
whenever it is installed, as `uvicorn[standard]` installs it, and `httptools` buffers a header
block of any size. uvicorn also rewrites the client address and scheme from `X-Forwarded-*`, and
writes `server: uvicorn` on every answer. No WebSocket route exists, and no upgrade is
served: uvicorn performs none, and a `websocket` scope is refused before routing, so a route added
later is never served unauthenticated.

**Python's `Money` now accepts any exponent Java's `BigDecimal` accepts.** The decimal module's
default context stops the exponent at 999999, in either direction, and past it an operation raises
`decimal.InvalidOperation` or `decimal.Overflow`, neither of which is the `ValueError` the module
promises. [ADR-018](ADR-018-cross-language-money-vectors.md) pins the two kernels to agree.

## Decision

**A body over 64 KiB is refused with 413 `CONTENT_TOO_LARGE`, by all three services.** The
owner decided the limit on 2026-10-31. It is one value in each codebase, `MAX_REQUEST_BODY_BYTES`, because one gateway relays to both
services and one client reads every service's problems (ADR-026).

- **The code.** `CONTENT_TOO_LARGE` is RFC 9110's name for status 413 and what Spring's own 413
  already carried on the ledger.
- **The ledger and the gateway.** Each has a `RequestBodyLimitFilter`, the gateway's a deliberate
  copy of the ledger's, as its `HostAllowListFilter` is. It is registered after the Host
  allow-list and Spring MVC's request observation and ahead of the form-content filter, Spring
  Security and, on the gateway, the relay. A declared `Content-Length` over the limit is refused
  unread; a body of undeclared length is read up to one byte past the limit, and refused if it runs
  over. Both refuse before Spring Security, so before authentication. `RequestBodyLimitIT` and
  `GatewayApplicationIT` read that order from the running servers.
- **The assistant.** `RequestBodyLimit`, a pure ASGI middleware outside `BearerAuthentication`: the
  order is `HostAllowList`, correlation, body limit, authentication, routes. A declared length over
  the limit is refused before authentication and before anything reads the body. An undeclared
  one, such as a chunked body, is counted as the route reads it, which is after authentication: the
  read that crosses the limit raises a 413 in place of the body, and an unauthenticated chunked
  body is answered 401 and never read.

**The ledger bounds the three inputs that set the work.**

- **A horizon spans at most 100 years.** `Horizon.MAXIMUM_SPAN_YEARS`, checked as the horizon is
  built, so a longer one is 422 `RULE_VIOLATED` before anything is projected. **The 100-year figure
  is the owner's decision, made on 2026-10-30.**
- **A comparison takes at most 10 scenarios.** `Comparison.MAXIMUM_SCENARIOS` is the domain's
  limit, and `ComparisonController` checks it first, so more is a 400 `MALFORMED_REQUEST` naming
  the `scenario` parameter. **The ten-scenario cap is the owner's decision, made on 2026-10-30
  with the horizon.**
- **A date falls in the years 1 to 9999.** `SupportedDates` bounds a query parameter and a body
  member alike; one outside is a 400 `MALFORMED_REQUEST`, as a date that does not parse is.
  **The range is the owner's decision, made on 2026-10-31.**
- **An unparseable multipart body is a 400 `MALFORMED_REQUEST`**, with a fixed detail, in
  `LedgerProblemHandler`. A body over the multipart upload limit stays a 413.
- **A firewall rejection is a 400 `MALFORMED_REQUEST` problem.** The ledger's
  `ProblemRequestRejectedHandler` answers it with a fixed detail that names nothing from the
  request, after marking the observation as Spring's default handler does, as the gateway's does.
- **A failing row stays out of the log.** The ledger sets pgjdbc's `logServerErrorDetail` to
  `false`. Every 5xx is logged with its exception, and pgjdbc would otherwise append the server's
  detail line to the message, which for a `CHECK` or `NOT NULL` violation quotes the failing row.
  The error and the constraint's name remain.

**The assistant's server is bounded and quiet.** `adapters/__main__.py` starts uvicorn with:

- **`h11`**, uvicorn's pure-Python implementation, with `h11_max_incomplete_event_size` at 16 KiB.
  A request line and header block that is still incomplete past it is refused 400 before the
  application sees it. The bound is on the buffer, not an exact header limit: a whole block that
  arrives in one read can exceed it by up to that read's size.
- **`proxy_headers=False`**, because no proxy sits in front that the assistant should believe.
- **`server_header=False`**, so no answer names the software.
- **`ws="none"`**, so uvicorn has no WebSocket implementation and performs no upgrade at all. An
  `Upgrade: websocket` request is answered as the plain HTTP request it also is.
  `BearerAuthentication` still refuses any `websocket` scope before routing, token or none, as
  defence in depth for any other launcher, until how a WebSocket authenticates is designed. The
  coverage test in `tests/test_authorization.py` fails the build for a route of any type that
  declares no permission.

**`Money` in Python builds every context over the widest exponent range the decimal module
allows.** `_widest` replaces each context the module computes in, so an amount or a product past
the default limit is accepted, as `BigDecimal` accepts it, and no operation raises outside
`ValueError`.

## Consequences

**Makes easy**

- **No request is read past 64 KiB by any service.** A caller pays for a refusal, not for the
  work the body would have asked. On the gateway the refusal is the documented 413, not a relay
  that fails part-way.
- **One refusal, one code, on all three services,** for the one client that reads their
  problems.
- **A projection and a comparison have a ceiling.** A horizon is at most 100 years and a
  comparison at most 10 projections, whatever the request says.
- **A date either parses into a range both `java.time` and PostgreSQL accept, or is a 400.**
  Neither reaches the store.
- **The two kernels agree past the default exponent limit.** Both accept it.

**Makes hard**

- **A legitimate larger body needs the constant changed, in three places.** A bulk import would be
  the first. The limit is global, not per route, in each service.
- **A caller without a token or a permission can learn the limit.** On the ledger and the gateway
  any over-limit body, and on the assistant a declared one, is answered 413 before 401 or 403. The
  order spares the work, and the limit is no secret.
- **The assistant's chunked case is the weaker one.** Its body is counted as the route reads it,
  so an unauthenticated one is answered 401 and never read, but an authenticated caller's chunked
  body is read up to the limit before it is refused.
- **A plan longer than 100 years, or a comparison of eleven scenarios, is not possible.** Each
  bound is the owner's decision, as the body limit and the date range are, and raising one is the
  owner's decision again.
- **A year outside 1 to 9999 is refused,** though PostgreSQL could hold it. The query formatter
  parses every `LocalDate` as ISO and overrides any `@DateTimeFormat` pattern, so a query date in
  another pattern would need another bound.
- **The assistant serves through `h11`, and upgrades no WebSocket.** It gives up the throughput `httptools` would add, which
  one owner does not notice. A bound on the buffer is not a header-size contract.
- **The assistant trusts no forwarded header.** Behind a proxy it would see the proxy's address
  and scheme, until `proxy_headers` is revisited with the proxy.
- **A WebSocket route cannot be added by adding it.** uvicorn performs no upgrade, the middleware
  refuses the scope anyway, and a test fails for a route that declares no permission.
- **Python's `Money` no longer refuses an amount past the default exponent limit.** It computes
  on it, as `BigDecimal` does. What keeps such an amount off the wire is the body limit, not the
  kernel.

**Accepted cost**

- **Three copies of one limit.** The ledger and the gateway share no code, and the assistant is
  Python, so each holds its own constant. The assistant's `test_body_limit.py` pins its value
  against a literal restating the agreed 64 KiB; no test reads one codebase's constant from
  another's.

## Alternatives considered

- **A `maxLength` on every decimal in the contracts.** Deferred to the API scan
  ([#344](https://github.com/NaimElijah/FinAssist/issues/344)): the body cap already bounds a
  decimal, and a contract `maxLength` couples all three contracts and the generated web types.
  **Revisit when** #344 decides the contracts' bounds, or a decimal member needs a tighter bound
  than the body cap gives.
- **Per-field `@Size` bounds on text.** Not now: the body cap bounds them, and a number chosen per
  field would be a second source of truth. **Revisit when** one text field must be bounded below
  the body cap for a rule's sake, such as a stored description's length.
- **Starlette's `RequestBodyLimitMiddleware` on the assistant.** Rejected: it refuses in plain
  text, not in the service's problem shape, and it checks a declared length only once the
  application reads the body or answers, so the request would be authenticated first.
- **A per-route limit.** Not now: every endpoint takes a body of a few kilobytes at most.
  **Revisit when** a legitimate request body nears the limit.
- **`httptools` with a header bound set elsewhere.** Rejected: `httptools` buffers a header block
  of any size, and `h11` is the implementation that has a bound to set.

## Revisit when

- **A legitimate request body nears the limit.** An endpoint that takes more than a few kilobytes
  makes the limit per route, in every service that relays or serves it.
- **A deployment puts a proxy in front of the assistant** (#61). `proxy_headers` and the trusted
  hosts it needs are decided with it.
- **Someone designs how a WebSocket authenticates.** The blanket refusal gives way to that design.
- **The owner plans past 100 years, compares more than 10 scenarios, or needs a year outside 1 to
  9999.** The owner decides the figure again.

## What lands with this record

No code beyond #340's own fixes. Every rule above exists and has a test:

- **The body limit, ledger.** `RequestBodyLimitIT`:
  `Given_a_declared_length_one_byte_over_the_limit_When_posted_Then_it_is_a_413_and_nothing_is_recorded`,
  `Given_a_chunked_body_one_byte_over_the_limit_When_posted_Then_it_is_a_413_and_nothing_is_recorded`,
  the two cases for a body of exactly the limit, and
  `Given_the_running_ledger_When_its_filter_chain_is_read_Then_the_body_limit_runs_after_the_host_allow_list_and_before_any_body_reader`.
- **The body limit, gateway.** `GatewayLoginFlowIT`:
  `Given_a_signed_in_session_When_a_relayed_write_declares_a_body_over_the_limit_Then_it_is_refused_413_and_not_relayed`,
  `Given_a_signed_in_session_When_a_relayed_write_sends_a_chunked_body_over_the_limit_Then_it_is_refused_413_and_not_relayed`
  and
  `Given_a_signed_in_session_When_a_relayed_write_carries_a_body_of_exactly_the_limit_Then_it_is_relayed_whole`;
  `GatewayApplicationIT`:
  `Given_the_running_gateway_When_its_filter_chain_is_read_Then_the_body_limit_runs_after_the_host_allow_list_and_before_any_body_reader`.
- **The body limit, assistant.** `tests/test_body_limit.py`:
  `Given_a_content_length_over_the_limit_When_posted_Then_it_is_refused_413_before_it_is_read`,
  `Given_no_token_and_a_content_length_over_the_limit_When_posted_Then_it_is_413_not_401`,
  `Given_no_token_and_a_chunked_body_over_the_limit_When_posted_Then_it_is_401_and_never_read`,
  `Given_a_chunked_body_over_the_limit_When_posted_Then_it_is_refused_413_once_it_crosses_it` and
  `Given_the_body_limit_When_read_Then_it_is_the_64_KiB_every_service_enforces`.
- **The ledger's bounds.** `HorizonTest`; `ProjectionControllerTest`:
  `Given_a_horizon_one_day_beyond_the_maximum_span_When_projected_Then_it_is_a_422_and_nothing_is_projected`;
  `ComparisonControllerTest`:
  `Given_more_scenarios_than_the_maximum_When_compared_Then_it_is_a_400_problem_naming_the_scenario_parameter`;
  `ComparisonTest`:
  `Given_more_projections_than_the_maximum_When_compared_Then_it_is_refused`;
  `AccountingHttpIT`:
  `Given_a_body_date_after_year_9999_When_recorded_Then_it_is_a_400_malformed_request_and_nothing_is_recorded`
  and its query-parameter siblings; `LedgerApplicationIT`:
  `Given_a_multipart_request_naming_no_boundary_When_it_reaches_the_ledger_Then_it_is_a_400_and_logs_no_error`,
  `Given_a_path_the_firewall_rejects_When_answered_Then_it_is_a_400_problem_with_a_code` and
  `Given_the_ledger_data_source_When_a_statement_violates_a_constraint_Then_the_message_carries_no_server_detail`;
  and `RequestRejectedHandlerTest`:
  `Given_a_request_the_firewall_rejects_When_it_is_handled_Then_the_current_observation_is_marked_failed_and_a_400_problem_is_written`.
- **The assistant's server.** `tests/test_observability.py`:
  `Given_the_entry_point_When_it_starts_the_server_Then_the_header_block_is_bounded`,
  `Given_the_entry_point_When_it_starts_the_server_Then_proxy_and_server_headers_are_off`,
  `Given_headers_over_the_bound_When_the_entry_point_serves_them_Then_they_are_refused_400`,
  `Given_the_entry_point_When_it_starts_the_server_Then_no_websocket_is_upgraded` and
  `Given_a_websocket_upgrade_When_the_entry_point_serves_it_Then_it_is_answered_as_plain_http`;
  `tests/test_authorization.py`:
  `Given_a_websocket_upgrade_When_any_path_is_requested_Then_it_is_refused_before_routing`.
- **`Money`.** `tests/test_money.py`:
  `Given_an_amount_past_a_million_integer_digits_When_constructed_Then_it_is_accepted` and
  `Given_a_product_past_a_million_integer_digits_When_times_rounded_Then_it_is_accepted`.

Documentation:

- **Status lines and README.** ADR-028 reads `Accepted, extended by ADR-030, ADR-056 and
  ADR-058`, and ADR-030 `Accepted, extended by ADR-037, ADR-053, ADR-057 and ADR-058, corrected by
  ADR-047`, each with its row in `docs/ADRs/README.md`, a row for 058 and a note.
- **`backend/ledger/CLAUDE.md`.** The body limit, the date range, the horizon and scenario
  bounds, the multipart 400, the firewall handler and `logServerErrorDetail`.
- **`backend/gateway/CLAUDE.md`.** The gateway's copy of the body limit.
- **`backend/assistant/CLAUDE.md`.** The body limit, `h11` and its header bound, the proxy and
  server headers, `ws`, and `Money`.
- **`docs/architecture/architecture.md`.** The limits as built.
