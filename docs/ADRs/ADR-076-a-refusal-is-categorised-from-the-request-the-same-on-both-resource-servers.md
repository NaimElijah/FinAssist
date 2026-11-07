# ADR-076: A refusal is categorised from the request, the same on both resource servers

- **Status:** Accepted
- **Date:** 2026-11-07

Extends [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md), which fixes the reason
categories a refused token is logged under and gives "a request that sent no token at all" a bare
`Bearer` challenge, without saying how either is decided.

## Context

The root `CLAUDE.md` requires the ledger and the assistant to log a refused token the same way and
to stay symmetric, so that a rule written against one service's logs reads the other's. Scan 1 of M0
([#338](https://github.com/NaimElijah/FinAssist/issues/338)) found three request shapes where they
differed ([#352](https://github.com/NaimElijah/FinAssist/issues/352)):

| Request | Ledger | Assistant |
|---|---|---|
| A token signed HS256 | `unknown_key` | `malformed` |
| `Authorization: Bearer a b` or `Bearer a,b` | `missing` | `malformed` |
| `Authorization: Bearer`, `Bearer   ` or `Basic …` | `missing`, with `invalid_token` | `missing`, with a bare `Bearer` |

The causes were where each service decided:

- **The assistant decides from the request.** Its `_require_algorithm` reads the token's header
  before any key lookup. Its `_bearer_token` treats a header that is not `Bearer` followed by a
  credential as no credential at all.
- **The ledger decided from its libraries.**
  - It read the category out of Nimbus's English failure message. For HS256 that is "Another
    algorithm expected, or no matching key(s) found", which can only be read as `unknown_key`.
  - It read any refusal with no decoder exception behind it as `missing`. Spring's
    `DefaultBearerTokenResolver` refuses `Bearer a b` before decoding, with no cause.
  - It chose the challenge by whether any `Authorization` header was present at all.

Both services answered 401 and neither logged the token, so nothing leaked. But on-call saw one
attack under two names, and the ledger's categories rested on a library's wording.

## Decision

**The ledger reads the request the way the assistant does.** The owner chose this direction on
2026-11-07.

- **A token whose header cannot be read, or names any algorithm but RS256, is `malformed`,** and is
  refused before the key source is asked. On the ledger, `RefuseUnexpectedAlgorithm` wraps the
  decoder and reports the category through the same `JwtValidationException` the token validators
  use, so the entry point reads it typed. The decoder's own `jwsAlgorithm(RS256)` stays the
  defence, as `jwt.decode`'s allow-list does on the assistant.
- **A request carries a bearer credential only when its `Authorization` header is `Bearer`,
  matched ignoring case, followed by a non-blank credential.** `Bearer` alone, `Bearer` with only
  spaces after it, and any other scheme carry none.
  - With none: `missing`, and the bare `Bearer` challenge. RFC 6750 §3 asks for no error code when
    a request lacks any authentication information.
  - With one that cannot be read: `malformed`, and `Bearer error="invalid_token"`.

  On the ledger, `ProblemAuthenticationEntryPoint` reads the header itself for both the category
  and the challenge, mirroring `_bearer_token`.

| Request | Both services |
|---|---|
| HS256, `alg: none`, a header that cannot be read, `Bearer a b`, `Bearer a,b` | 401 `malformed`, `invalid_token` |
| No header, `Bearer`, `Bearer   `, `Basic …` | 401 `missing`, bare `Bearer` |

Pinned on the ledger by `LedgerAuthorizationIT`: the `HS256`, `RS512`, `alg none` and unreadable
header rows of `categorisedBadTokens`, and
`Given_an_authorization_header_without_a_usable_bearer_token_When_refused_Then_its_shape_sets_the_reason_and_the_challenge`.
Pinned on the assistant by the matching cases in `test_auth.py` and by the test of the same name
in `test_authorization.py`.

## Consequences

- **One refusal, one name,** on both services, for every shape above. A log rule or an alert
  written against one reads the other.
- **Fewer categories rest on Nimbus's wording.** Three remain read from its messages: `unknown_key`,
  a payload that is not a claims set (`malformed`), and anything else (`rejected`).
  `LedgerAuthorizationIT` fails if an upgrade rewords them.
- **The ledger's challenge changes for two headers.** A `Basic` header, or `Bearer` with no
  credential, was answered `Bearer error="invalid_token"` and is now answered with a bare `Bearer`.
  Only `ops/smoke/auth_smoke.py` reads the ledger's challenge, for a request with no
  `Authorization` header, whose challenge is unchanged. The gateway relays the 401 unread, and the
  web client never reads the header.
- **An algorithm-confusion token costs no JWKS fetch and no Nimbus processing.** The header is
  parsed twice for an RS256 token, once here and once by the decoder. That is a few microseconds
  against a signature check.
- **A token in an `access_token` parameter is ignored by both services.** Neither enables Spring's
  query or form resolution: `DefaultBearerTokenResolver` reads the parameter only when
  `allowUriQueryParameter` or `allowFormEncodedBodyParameter` is set, and both default to off.

## Alternatives considered

- **The assistant follows the ledger.** Rejected:
  - `unknown_key` for HS256 claims something about the key set that the token's algorithm alone
    cannot tell;
  - `missing` for `Bearer a b` hides a credential that was sent;
  - the ledger's categories would still rest on English messages.
- **Categorise HS256 from Nimbus's message, as before, but map it to `malformed`.** Rejected: the
  message "Another algorithm expected, or no matching key(s) found" is the same for an unknown key,
  so no wording tells the two apart.
- **Leave a blank `Bearer` and a `Basic` header split, and fix only the named shapes.** Rejected by
  the owner: it leaves one header named two ways, which is what this record exists to end.

## Revisit when

- **A third resource server appears.** It reads the request the same way, or this record is
  extended.
- **The ledger accepts a token from anywhere but the `Authorization` header,** such as a query
  parameter. "Carries a bearer credential" then needs a definition that covers it.
