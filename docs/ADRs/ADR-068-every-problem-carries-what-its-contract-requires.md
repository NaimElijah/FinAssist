# ADR-068: Every problem carries what its contract requires

- **Status:** Accepted
- **Date:** 2026-10-31

Extends [ADR-028](ADR-028-http-wire-conventions.md). ADR-028 makes every failure an RFC 9457
problem with a stable `code`, and answers a malformed request 400 "with `errors[]`". This record
settles which members a problem always carries, which 400s list `errors`, and what the three
contracts promise about both.

## Context

**Scan 7 of 12 read the three services' contracts as one API on 2026-10-31**
([#344](https://github.com/NaimElijah/FinAssist/issues/344)). It found that the contracts promised
less than the services sent, and that the ledger sent less than ADR-028 said.

- **The contracts required `code` alone.** In each `Problem` schema the only required member was
  `code`, and an `errors` entry required nothing. But every writer in every service sends `type`,
  `title` and `status`, and every `errors` entry has a `field` and a `message`.
- **The web client already relies on more.** `isProblem` in `client.ts` refuses a problem without a
  numeric `status`. Its own comment said the contracts mark that member optional.
- **The ledger's `errors[]` came from bean validation alone.** An unreadable body, a body member
  Jackson refused, a parameter of the wrong type and a missing parameter were each answered 400
  with Spring's own detail and no `errors`. ADR-028's table lists all four under "400, with
  `errors[]`". The assistant already named the field for each.
- **Spring's type-mismatch detail quoted the rejected value** back to the caller.
- **One writer could drop `title`.** `ProblemDetail` takes its title from the status's reason
  phrase, and a status with no `HttpStatus` constant has none. The mix-in then left `title` out.
  Only a test route reached that path, but the fallback handler accepts any status.
- **`instance` is relative to the service that answered.** Behind the gateway, the browser called
  `/ledger/api/v1/…` and the ledger saw `/api/v1/…`. Nothing in the value says which.

## Decision

**Every problem carries `type`, `title`, `status` and `code`, and every `errors` entry a `field`
and a `message`.** All three contracts require them, in one `Problem` schema that
`GatewayContractIT` and `tests/test_contract.py` hold identical to the ledger's.

- **The ledger titles a status with no reason phrase "Unexpected Status".**
  `LedgerProblemHandler.createResponseEntity` sets it when no title is set, so `title` is never
  dropped.
- **`detail`, `instance` and `errors` stay optional.** Some problems carry none of them.

**Every 400 for a malformed JSON body or parameter lists `errors[]`, naming what it refused.**
Bean validation lists every constraint a body or parameter breaks. Jackson stops at the first value
it refuses, so an unreadable body lists that one.

- **A body member Jackson refused** is named by its wire path, as bean validation names a field:
  `entries[1].amount.currency`. That covers an unknown enum constant, a malformed UUID or date, and
  an integer that overflows its type.
- **A parameter of the wrong type, or a required one left out,** is named by the parameter's name.
- **A body with no member to name** is `body`: unparseable JSON, no body at all, or a document of
  the wrong shape. The assistant names `body` for unparseable JSON too, not the character offset
  FastAPI reports.
- **Every `message`, and the problem's `detail`, is fixed text.** Neither quotes what was sent:
  not Jackson's message, not Spring's detail, not the rejected value. The detail is the assistant's:
  "The request could not be read; each entry in errors names why."

**A 400 with nothing to name lists no `errors`.** That covers a URL the firewall refuses and a
multipart body that cannot be parsed. Each is still 400 `MALFORMED_REQUEST`.

**`instance` describes itself in the contract**: the request's path as the answering service
received it, so behind the gateway it omits the gateway's `/ledger` or `/assistant` prefix.

**Codes are not yet enumerated in any contract.** Naming each service's codes is
[#406](https://github.com/NaimElijah/FinAssist/issues/406).

## Consequences

**Makes easy**

- **A client generated from a contract can rely on what it reads.** The generated types make
  `status`, `title`, `type` and `code` non-optional, and a form can place every malformed-input
  refusal on its field.
- **The two services read alike.** The same mistake gets the same shape and the same detail from
  the ledger and the assistant.
- **Less of the caller's input is reflected back.** The type-mismatch detail no longer quotes the
  value.

**Makes hard**

- **A new problem writer must send all four members.** A test fails if it does not:
  `LedgerProblemHandlerTest`, the per-writer tests, and the assistant's `_problem` helper in
  `tests/test_problems.py`, which checks every problem that suite reads.
- **Changing the `Problem` schema means changing three builders together**: `OpenApiConfiguration`,
  `GatewayOpenApiConfiguration` and `adapters/openapi.py`. The identity tests refuse anything else.
- **`field` reflects names from the code, never from the caller.** That holds because Spring Boot
  leaves `FAIL_ON_UNKNOWN_PROPERTIES` off and no request type takes a map. A test pins that an
  unknown member's name never appears in a 400. It pairs the unknown member with a known one that
  bean validation refuses, since Jackson reports a known member it refuses ahead of an unknown one
  and would hide the leak.

## Alternatives considered

- **Keep `code` the only required member.** Rejected: the web client already depends on `status`,
  and a contract that promises less than its consumer relies on cannot catch a regression.
- **Require `detail` as well.** Rejected: a writer may omit it, and RFC 9457 makes it optional.
- **Pass Jackson's or Spring's message through as the `message`.** Rejected: both quote the input,
  and a problem body is no place to reflect it back.
- **Map a status with no reason phrase to 500.** Rejected: it changes a status the fallback handler
  answers today, to fix a member that a fixed title fixes.
- **Leave `instance` undescribed, or drop it.** Rejected: dropping it removes a member clients may
  log, and leaving it undescribed leaves a consumer to resolve it against the wrong base.

## Revisit when

- **A request type takes a map, or unknown members are refused.** The test that pins "no unknown
  name in a 400" turns red. Decide then whether a caller-chosen name may be reflected.
- **#406 lands.** The `code` member gains its enumeration, and this record's schema text changes
  with it.

## What lands with this record

- **The schema:** `OpenApiConfiguration.problemSchema()` (ledger),
  `GatewayOpenApiConfiguration.problemSchema()` (gateway) and `PROBLEM_SCHEMA` (assistant), with
  the three contracts and the web client's generated types regenerated.
- **The ledger's handler:** `LedgerProblemHandler` overrides `handleHttpMessageNotReadable`,
  `handleTypeMismatch` and `handleMissingServletRequestParameter`, and titles an unnamed status.
- **The assistant's handler:** `_malformed_request` names `body` for `json_invalid`.
- **The tests:**
  - `LedgerProblemHandlerTest`, `AccountControllerTest` and `TransactionControllerTest` hold each
    400 to its named entry.
  - `OpenApiConfigurationTest` holds the required members and the `instance` description.
  - `tests/test_problems.py` holds every problem to the required members.
  - `tests/test_portfolio_api.py` holds unparseable JSON to `body`.
